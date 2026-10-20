package io.localplayer.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseAssetTest {
    private val signer="a".repeat(64)
    private fun release():JSONObject {
        val name="yingxi-1.2.3-alpha.1-123.apk"
        val asset=JSONObject().put("name",name).put("state","uploaded").put("size",100).put("digest","sha256:"+"b".repeat(64))
            .put("browser_download_url","https://github.com/${AppUpdates.REPOSITORY}/releases/download/v1.2.3-alpha.1/$name")
        return JSONObject().put("tag_name","v1.2.3-alpha.1").put("body","Release notes").put("assets",JSONArray().put(asset))
    }
    private fun reject(block:()->Unit){try{block();fail("Invalid asset must be rejected")}catch(_:IllegalArgumentException){}catch(_:java.io.IOException){}}
    @Test fun parsesApkWithoutManifestAndUsesInstalledSigningIdentity(){
        val parsed=AppUpdates.parseAssetRelease(release(),signer)!!
        assertEquals(AppVersion("1.2.3-alpha.1",123),parsed.version);assertEquals("b".repeat(64),parsed.sha256);assertEquals(signer,parsed.signer)
    }
    @Test fun missingOrMalformedDigestNeverFallsBackToSizeOnly(){
        for(digest in listOf("","md5:"+"b".repeat(32),"sha256:short")){
            val release=release();release.getJSONArray("assets").getJSONObject(0).put("digest",digest)
            reject{AppUpdates.parseAssetRelease(release,signer)}
        }
    }
    @Test fun ambiguousApksAndForeignUrlsAreRejected(){
        val duplicate=release();duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0))
        reject{AppUpdates.parseAssetRelease(duplicate,signer)}
        val foreign=release();foreign.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://unrelated.invalid/app.apk")
        reject{AppUpdates.parseAssetRelease(foreign,signer)}
    }
    @Test fun incompleteAndMismatchedAssetsAreRejected(){
        val uploading=release();uploading.getJSONArray("assets").getJSONObject(0).put("state","open")
        reject{AppUpdates.parseAssetRelease(uploading,signer)}
        val wrong=release();wrong.getJSONArray("assets").getJSONObject(0).put("name","yingxi-1.2.4-alpha.1-123.apk")
        assertNull(AppUpdates.parseAssetRelease(wrong,signer))
        assertNull(AppUpdates.parseAssetRelease(JSONObject().put("tag_name","ios-v0.1.0-alpha.2"),signer))
    }
}
