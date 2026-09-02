package io.github.gjr787878.devicereset.hooks;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.net.Uri;

import io.github.gjr787878.devicereset.xposed.Identity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook GSF ID (Google Services Framework ID)。
 * GSF ID通过查询Google Play Services的ContentProvider获取。
 */
public class GsfIdHook {

    private static final Uri GSF_URI = Uri.parse(
            "content://com.google.android.gsf.gservices");

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, Identity identity) {
        if (identity.gsfId == null) return;

        // Hook ContentResolver.query()，拦截对gsf的查询
        try {
            XposedHelpers.findAndHookMethod(
                    ContentResolver.class,
                    "query",
                    Uri.class,
                    String[].class,
                    String.class,
                    String[].class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Uri uri = (Uri) param.args[0];
                            if (uri == null) return;

                            String uriStr = uri.toString();
                            if (uriStr.contains("com.google.android.gsf.gservices")) {
                                Cursor original = (Cursor) param.getResult();
                                if (original != null) {
                                    // 替换cursor中的android_id值
                                    param.setResult(new GsfCursorWrapper(original, identity.gsfId));
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // 也hook另一个重载版本 query(Uri, String[], Bundle, CancellationSignal)
        try {
            XposedHelpers.findAndHookMethod(
                    ContentResolver.class,
                    "query",
                    Uri.class,
                    String[].class,
                    android.os.Bundle.class,
                    android.os.CancellationSignal.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Uri uri = (Uri) param.args[0];
                            if (uri == null) return;

                            String uriStr = uri.toString();
                            if (uriStr.contains("com.google.android.gsf.gservices")) {
                                Cursor original = (Cursor) param.getResult();
                                if (original != null) {
                                    param.setResult(new GsfCursorWrapper(original, identity.gsfId));
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}
    }

    /**
     * Cursor包装类，替换GSF查询结果中的android_id。
     */
    private static class GsfCursorWrapper implements Cursor {
        private final Cursor original;
        private final String fakeGsfId;

        GsfCursorWrapper(Cursor original, String fakeGsfId) {
            this.original = original;
            this.fakeGsfId = fakeGsfId;
        }

        @Override
        public int getCount() { return original.getCount(); }

        @Override
        public int getPosition() { return original.getPosition(); }

        @Override
        public boolean move(int offset) { return original.move(offset); }

        @Override
        public boolean moveToPosition(int position) { return original.moveToPosition(position); }

        @Override
        public boolean moveToFirst() { return original.moveToFirst(); }

        @Override
        public boolean moveToLast() { return original.moveToLast(); }

        @Override
        public boolean moveToNext() { return original.moveToNext(); }

        @Override
        public boolean moveToPrevious() { return original.moveToPrevious(); }

        @Override
        public boolean isFirst() { return original.isFirst(); }

        @Override
        public boolean isLast() { return original.isLast(); }

        @Override
        public boolean isBeforeFirst() { return original.isBeforeFirst(); }

        @Override
        public boolean isAfterLast() { return original.isAfterLast(); }

        @Override
        public int getColumnIndex(String columnName) { return original.getColumnIndex(columnName); }

        @Override
        public int getColumnIndexOrThrow(String columnName) throws IllegalArgumentException {
            return original.getColumnIndexOrThrow(columnName);
        }

        @Override
        public String getColumnName(int columnIndex) { return original.getColumnName(columnIndex); }

        @Override
        public String[] getColumnNames() { return original.getColumnNames(); }

        @Override
        public int getColumnCount() { return original.getColumnCount(); }

        @Override
        public byte[] getBlob(int columnIndex) { return original.getBlob(columnIndex); }

        @Override
        public String getString(int columnIndex) {
            // GSF查询结果通常是 key-value 两列：name, value
            // 当value列的值是android_id对应的数字时，替换为假的GSF ID
            String value = original.getString(columnIndex);
            if (value != null && columnIndex == 1) {
                // 检查前一列是否是 android_id
                String key = original.getString(0);
                if ("android_id".equals(key)) {
                    // GSF ID是16位hex转成的十进制长整型
                    try {
                        long gsfLong = Long.parseLong(fakeGsfId, 16);
                        return String.valueOf(gsfLong);
                    } catch (NumberFormatException e) {
                        return value;
                    }
                }
            }
            return value;
        }

        @Override
        public short getShort(int columnIndex) { return original.getShort(columnIndex); }

        @Override
        public int getInt(int columnIndex) { return original.getInt(columnIndex); }

        @Override
        public long getLong(int columnIndex) {
            String value = getString(columnIndex);
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return original.getLong(columnIndex);
            }
        }

        @Override
        public float getFloat(int columnIndex) { return original.getFloat(columnIndex); }

        @Override
        public double getDouble(int columnIndex) { return original.getDouble(columnIndex); }

        @Override
        public int getType(int columnIndex) { return original.getType(columnIndex); }

        @Override
        public boolean isNull(int columnIndex) { return original.isNull(columnIndex); }

        @Override
        public void deactivate() { original.deactivate(); }

        @Override
        public boolean requery() { return original.requery(); }

        @Override
        public void close() { original.close(); }

        @Override
        public boolean isClosed() { return original.isClosed(); }

        @Override
        public void registerContentObserver(android.database.ContentObserver observer) {
            original.registerContentObserver(observer);
        }

        @Override
        public void unregisterContentObserver(android.database.ContentObserver observer) {
            original.unregisterContentObserver(observer);
        }

        @Override
        public void registerDataSetObserver(android.database.DataSetObserver observer) {
            original.registerDataSetObserver(observer);
        }

        @Override
        public void unregisterDataSetObserver(android.database.DataSetObserver observer) {
            original.unregisterDataSetObserver(observer);
        }

        @Override
        public void setNotificationUri(ContentResolver cr, Uri uri) {
            original.setNotificationUri(cr, uri);
        }

        @Override
        public Uri getNotificationUri() { return original.getNotificationUri(); }

        @Override
        public boolean getWantsAllOnMoveCalls() { return original.getWantsAllOnMoveCalls(); }

        @Override
        public void setExtras(android.os.Bundle extras) { original.setExtras(extras); }

        @Override
        public android.os.Bundle getExtras() { return original.getExtras(); }

        @Override
        public android.os.Bundle respond(android.os.Bundle extras) { return original.respond(extras); }

        @Override
        public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
            original.copyStringToBuffer(columnIndex, buffer);
        }
    }
}
