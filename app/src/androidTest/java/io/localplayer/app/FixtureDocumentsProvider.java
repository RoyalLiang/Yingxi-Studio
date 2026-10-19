package io.localplayer.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;

/** The test APK's standalone provider cannot depend on the target APK's Kotlin runtime. */
public final class FixtureDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "io.localplayer.app.test.documents";
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE};
    @Override public boolean onCreate() { return true; }
    private String title(String id) throws FileNotFoundException {
        switch (id) {
            case "root": return "导航测试";
            case "movies": return "影片";
            case "shorts": return "短片";
            case "clip": return "示例.mp4";
            default: throw new FileNotFoundException(id);
        }
    }
    private void row(MatrixCursor cursor, String id) throws FileNotFoundException {
        String name = title(id);
        Object[] values = new Object[cursor.getColumnCount()];
        String[] columns = cursor.getColumnNames();
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: values[i] = id; break;
                case Document.COLUMN_DISPLAY_NAME: values[i] = name; break;
                case Document.COLUMN_MIME_TYPE: values[i] = id.equals("clip") ? "video/mp4" : Document.MIME_TYPE_DIR; break;
                case Document.COLUMN_SIZE: values[i] = id.equals("clip") ? 1024L : 0L; break;
                case Document.COLUMN_FLAGS: values[i] = 0; break;
            }
        }
        cursor.addRow(values);
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Root.COLUMN_ROOT_ID: values[i] = "fixture"; break;
                case Root.COLUMN_DOCUMENT_ID: values[i] = "root"; break;
                case Root.COLUMN_TITLE: values[i] = "导航测试"; break;
            }
        }
        cursor.addRow(values);
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        row(cursor, id);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        switch (parent) {
            case "root": row(cursor, "movies"); break;
            case "movies": row(cursor, "shorts"); row(cursor, "clip"); break;
            case "shorts": break;
            default: throw new FileNotFoundException(parent);
        }
        return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        throw new FileNotFoundException("No media content in navigation fixture");
    }
    @Override public boolean isChildDocument(String parent, String child) {
        if (parent.equals("root")) return child.equals("movies") || child.equals("shorts") || child.equals("clip");
        return parent.equals("movies") && (child.equals("shorts") || child.equals("clip"));
    }
}
