/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.icons;

import static android.content.res.Configuration.UI_MODE_NIGHT_MASK;
import static android.content.res.Configuration.UI_MODE_NIGHT_YES;
import static android.content.res.Resources.ID_NULL;
import static com.android.launcher3.icons.GraphicsUtils.getExpectedBitmapSize;
import static com.android.launcher3.icons.IconProvider.ICON_TYPE_CALENDAR;
import static com.android.launcher3.icons.IconProvider.ICON_TYPE_CLOCK;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;

import com.android.launcher3.icons.BitmapInfo.Extender;
import com.android.launcher3.icons.cache.BaseIconCache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import app.lawnchair.icons.CustomAdaptiveIconDrawable;
import app.lawnchair.icons.ExtendedBitmapDrawable;
import app.lawnchair.icons.IconPreferencesKt;

/**
 * Class to handle monochrome themed app icons
 */
@SuppressWarnings("NewApi")
public class ThemedIconDrawable extends FastBitmapDrawable {

    public static final String TAG = "ThemedIconDrawable";

    final ThemedBitmapInfo bitmapInfo;
    final int colorFg, colorBg;

    private final Rect mBadgeBounds;

    protected ThemedIconDrawable(ThemedConstantState constantState) {
        super(constantState.mBitmap, constantState.colorFg);
        bitmapInfo = constantState.bitmapInfo;
        colorBg = constantState.colorBg;
        colorFg = constantState.colorFg;

        mBadgeBounds = bitmapInfo.mUserBadge == null ? null :
            new Rect(0, 0, bitmapInfo.mUserBadge.getWidth(), bitmapInfo.mUserBadge.getHeight());
    }

    @Override
    protected void drawInternal(Canvas canvas, Rect bounds) {
        // Evergreen (SUI-7): draw one cached bitmap instead of a path, a vector and the badge on
        // every frame. The raster lives on the ThemedBitmapInfo, which survives rebinds (the
        // drawer creates a new drawable on every bind), and is keyed by size and colours.
        // The paint carries the drawable alpha and the disabled filter (SUI-B2).
        Bitmap raster = bitmapInfo.getRaster(bounds.width(), bounds.height(), colorBg, colorFg);
        mPaint.setAlpha(getAlpha());
        if (raster != null) {
            canvas.drawBitmap(raster, bounds.left, bounds.top, mPaint);
        }
        if (mBadgeBounds != null) {
            canvas.drawBitmap(bitmapInfo.mUserBadge, mBadgeBounds, getBounds(), mPaint);
        }
    }

    @Override
    public boolean isThemed() {
        return true;
    }

    @Override
    public ConstantState getConstantState() {
        return new ThemedConstantState(bitmapInfo, colorBg, colorFg);
    }

    static class ThemedConstantState extends FastBitmapConstantState {

        final ThemedBitmapInfo bitmapInfo;
        final int colorFg, colorBg;

        public ThemedConstantState(ThemedBitmapInfo bitmapInfo,
                                   int colorBg, int colorFg) {
            super(bitmapInfo.icon, bitmapInfo.color);
            this.bitmapInfo = bitmapInfo;
            this.colorBg = colorBg;
            this.colorFg = colorFg;
        }

        @NonNull
        @Override
        public FastBitmapDrawable newDrawable() {
            return new ThemedIconDrawable(this);
        }
    }

    public static class ThemedBitmapInfo extends BitmapInfo {

        final ThemeData mThemeData;
        final float mNormalizationScale;
        final Bitmap mUserBadge;

        // Evergreen (SUI-7): background and monochrome layer rasterized per size and colours.
        // Two slots: a folder item is drawn both in the folder preview and in the open folder.
        private final Raster[] mRasters = new Raster[2];
        private int mNextRaster;

        private static final class Raster {
            final Bitmap bitmap;
            final int colorBg, colorFg;

            Raster(Bitmap bitmap, int colorBg, int colorFg) {
                this.bitmap = bitmap;
                this.colorBg = colorBg;
                this.colorFg = colorFg;
            }

            boolean matches(int width, int height, int bg, int fg) {
                return bitmap.getWidth() == width && bitmap.getHeight() == height
                        && colorBg == bg && colorFg == fg;
            }
        }

        @Nullable
        Bitmap getRaster(int width, int height, int colorBg, int colorFg) {
            if (width <= 0 || height <= 0) {
                return null;
            }
            for (Raster r : mRasters) {
                if (r != null && r.matches(width, height, colorBg, colorFg)) {
                    return r.bitmap;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(mNormalizationScale, mNormalizationScale, width / 2f, height / 2f);
            AdaptiveIconDrawable bgWrapper =
                    new CustomAdaptiveIconDrawable(new ColorDrawable(colorBg), null);
            bgWrapper.setBounds(0, 0, width, height);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setColor(colorBg);
            canvas.drawPath(bgWrapper.getIconMask(), paint);
            Drawable mono = mThemeData.loadMonochromeDrawable(colorFg);
            mono.setBounds(0, 0, width, height);
            mono.draw(canvas);
            canvas.setBitmap(null);
            mRasters[mNextRaster] = new Raster(bitmap, colorBg, colorFg);
            mNextRaster = (mNextRaster + 1) % mRasters.length;
            return bitmap;
        }

        public ThemedBitmapInfo(Bitmap icon, int color, ThemeData themeData,
                                float normalizationScale, Bitmap userBadge) {
            super(icon, color);
            mThemeData = themeData;
            mNormalizationScale = normalizationScale;
            mUserBadge = userBadge;
        }

        // Evergreen: keep the subclass. BaseIconCache applies withFlags() to every icon read from
        // the DB, which clones it; the base clone() returned a plain BitmapInfo, so themed icons
        // loaded from the cache lost their theme and only looked right after a full re-render.
        @Override
        public BitmapInfo clone() {
            return copyInternalsTo(new ThemedBitmapInfo(icon, color, mThemeData,
                    mNormalizationScale, mUserBadge));
        }

        @Override
        public FastBitmapDrawable newThemedIcon(Context context) {
            int[] colors = getThemedColors(context);
            FastBitmapDrawable drawable = new ThemedConstantState(this, colors[0], colors[1])
                .newDrawable();
            drawable.mDisabledAlpha = GraphicsUtils.getDisabledIconAlpha(context);
            return drawable;
        }

        @Nullable
        public byte[] toByteArray() {
            if (isNullOrLowRes()) {
                return null;
            }
            String resName = mThemeData.mResources.getResourceName(mThemeData.mResID);
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                getExpectedBitmapSize(icon) + 3 + resName.length());
            try {
                DataOutputStream dos = new DataOutputStream(out);
                dos.writeByte(TYPE_THEMED_V2);
                dos.writeFloat(mNormalizationScale);
                dos.writeUTF(mThemeData.mPackageName);
                dos.writeUTF(resName);
                icon.compress(Bitmap.CompressFormat.PNG, 100, dos);

                dos.flush();
                dos.close();
                return out.toByteArray();
            } catch (IOException e) {
                Log.w(TAG, "Could not write bitmap");
                return null;
            }
        }

        static ThemedBitmapInfo decode(byte[] data, int color,
                                       BitmapFactory.Options decodeOptions, UserHandle user, BaseIconCache iconCache,
                                       Context context) {
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
                dis.readByte(); // type
                float normalizationScale = dis.readFloat();

                String packageName = dis.readUTF();
                // Evergreen: memoized in the icon cache; this ran a binder call and a name lookup
                // for every themed icon read from the DB.
                Resources res = iconCache.getThemeResources(context, packageName);

                String resName = dis.readUTF();
                int resId = iconCache.getThemeResId(res, packageName, resName);
                if (resId == ID_NULL) {
                    return null;
                }

                Bitmap userBadgeBitmap = null;
                if (!Process.myUserHandle().equals(user)) {
                    try (BaseIconFactory iconFactory = iconCache.getIconFactory()) {
                        userBadgeBitmap = iconFactory.getUserBadgeBitmap(user);
                    }
                }

                ThemeData themeData = new ThemeData(res, packageName, resId);
                Bitmap icon = BitmapFactory.decodeStream(dis, null, decodeOptions);
                return new ThemedBitmapInfo(icon, color, themeData, normalizationScale,
                    userBadgeBitmap);
            } catch (IOException | PackageManager.NameNotFoundException e) {
                return null;
            }
        }
    }

    public static class ThemeData {

        final Resources mResources;
        final String mPackageName;
        final int mResID;

        public ThemeData(Resources resources, String packageName, @DrawableRes int resID) {
            mResources = resources;
            mPackageName = packageName;
            mResID = resID;
        }

        Drawable loadMonochromeDrawable(int accentColor) {
            Drawable d = mResources.getDrawable(mResID).mutate();
            d.setTint(accentColor);
            d = new InsetDrawable(d, .2f);
            return d;
        }

        public Drawable wrapDrawable(Drawable original, int iconType) {
            if (!(original instanceof AdaptiveIconDrawable) && !(original instanceof BitmapDrawable)) {
                return original;
            }
            String resourceType = mResources.getResourceTypeName(mResID);
            if (iconType == ICON_TYPE_CALENDAR && "array".equals(resourceType)) {
                TypedArray ta = mResources.obtainTypedArray(mResID);
                int id = ta.getResourceId(IconProvider.getDay(), ID_NULL);
                ta.recycle();
                return id == ID_NULL ? original
                    : wrapWithThemeData(original, new ThemeData(mResources, mPackageName, id));
            } else if (iconType == ICON_TYPE_CLOCK && "array".equals(resourceType)) {
                if (original instanceof ClockDrawableWrapper) {
                    ((ClockDrawableWrapper) original).mThemeData = this;
                }
                return original;
            } else if ("drawable".equals(resourceType)) {
                return wrapWithThemeData(original, this);
            } else {
                return original;
            }
        }

        private Drawable wrapWithThemeData(Drawable original, ThemeData themeData) {
            if (original instanceof AdaptiveIconDrawable) {
                return new ThemedAdaptiveIcon((AdaptiveIconDrawable) original, themeData);
            } else if (original instanceof BitmapDrawable) {
                return new ThemedBitmapIcon(mResources, (BitmapDrawable) original, themeData);
            }
            throw new IllegalArgumentException("original must be AdaptiveIconDrawable or BitmapDrawable");
        }
    }

    static class ThemedAdaptiveIcon extends CustomAdaptiveIconDrawable implements Extender {

        protected final ThemeData mThemeData;

        public ThemedAdaptiveIcon(AdaptiveIconDrawable parent, ThemeData themeData) {
            super(parent.getBackground(), parent.getForeground());
            mThemeData = themeData;
        }

        @Override
        public BitmapInfo getExtendedInfo(Bitmap bitmap, int color, BaseIconFactory iconFactory,
                                          float normalizationScale) {
            Bitmap userBadge = iconFactory.getUserBadgeBitmap(Process.myUserHandle());
            return new ThemedBitmapInfo(bitmap, color, mThemeData, normalizationScale, userBadge);
        }

        @Override
        public void drawForPersistence(Canvas canvas) {
            draw(canvas);
        }

        @Override
        public Drawable getThemedDrawable(Context context) {
            int[] colors = getThemedColors(context);
            Drawable bg = new ColorDrawable(colors[0]);
            float inset = getExtraInsetFraction() / (1 + 2 * getExtraInsetFraction());
            Drawable fg = new InsetDrawable(mThemeData.loadMonochromeDrawable(colors[1]), inset);
            return new CustomAdaptiveIconDrawable(bg, fg);
        }
    }

    static class ThemedBitmapIcon extends ExtendedBitmapDrawable implements Extender {

        protected final ThemeData mThemeData;

        public ThemedBitmapIcon(Resources res, BitmapDrawable parent, ThemeData themeData) {
            super(res, parent.getBitmap(), ExtendedBitmapDrawable.isFromIconPack(parent));
            mThemeData = themeData;
        }

        @Override
        public BitmapInfo getExtendedInfo(Bitmap bitmap, int color, BaseIconFactory iconFactory,
                                          float normalizationScale) {
            Bitmap userBadge = iconFactory.getUserBadgeBitmap(Process.myUserHandle());
            return new ThemedBitmapInfo(bitmap, color, mThemeData, normalizationScale, userBadge);
        }

        @Override
        public void drawForPersistence(Canvas canvas) {
            draw(canvas);
        }

        @Override
        public Drawable getThemedDrawable(Context context) {
            int[] colors = getThemedColors(context);
            Drawable bg = new ColorDrawable(colors[0]);
            float extraInsetFraction = CustomAdaptiveIconDrawable.getExtraInsetFraction();
            float inset = extraInsetFraction / (1 + 2 * extraInsetFraction);
            Drawable fg = new InsetDrawable(mThemeData.loadMonochromeDrawable(colors[1]), inset);
            return new CustomAdaptiveIconDrawable(bg, fg);
        }
    }

    @ColorInt
    public static int[] getThemedColors(Context context) {
        final int[] result = getColors(context);
        if (!IconPreferencesKt.shouldTransparentBGIcons(context)) {
            return result;
        }
        result[0] = 0;
        return result;
    }

    /**
     * Get an int array representing background and foreground colors for themed icons
     */
    @ColorInt
    public static int[] getColors(Context context) {
        if (COLORS_LOADER != null) {
            return COLORS_LOADER.apply(context);
        }
        Resources res = context.getResources();
        int[] colors = new int[2];
        if ((res.getConfiguration().uiMode & UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES) {
            colors[0] = res.getColor(android.R.color.system_neutral1_800);
            colors[1] = res.getColor(android.R.color.system_accent1_100);
        } else {
            colors[0] = res.getColor(android.R.color.system_accent1_100);
            colors[1] = res.getColor(android.R.color.system_neutral2_700);
        }
        return colors;
    }

    public static Function<Context, int[]> COLORS_LOADER;
}
