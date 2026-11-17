package com.doubao.autocheckin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 给自己安装包用的最小 ContentProvider（v1.5.7）：
 * 把 cacheDir/apk 下的 apk 以 content:// 暴露给系统安装器，
 * 免掉引入 androidx 的 FileProvider 依赖。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.doubao.autocheckin.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        // 只允许读自己缓存目录里的 apk，防路径穿越
        if (name == null || !name.endsWith(".apk")
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new FileNotFoundException("bad name: " + name);
        }
        File dir = new File(getContext().getCacheDir(), "apk");
        File f = new File(dir, name);
        if (!f.exists()) {
            throw new FileNotFoundException(f.getAbsolutePath());
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
