package io.localplayer.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Grants this test APK's own synthetic tree through the real activity grant path. */
public final class GrantFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri tree = Uri.parse(getIntent().getStringExtra("tree"));
        if (!FixtureDocumentsProvider.AUTHORITY.equals(tree.getAuthority())) { finish(); return; }
        Intent result = new Intent().setClassName(getIntent().getStringExtra("target"), "io.localplayer.app.MainActivity").setData(tree);
        result.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
            | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivity(result);
        finish();
    }
}
