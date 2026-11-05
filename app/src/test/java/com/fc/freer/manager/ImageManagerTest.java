package com.fc.freer.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ImageManagerTest {
    private static final String DID = "ab".repeat(32);
    private static final String ID = "cd".repeat(32);

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void publishIsFeip24AndOmitsBlanksAndTheSubject() {
        JsonObject feip = parse(ImageManager.publishJson("Logo", DID, "", Collections.emptyList(),
                "image/png", "  ", Collections.singletonList("(sid)" + ID)));
        assertEquals("FEIP", feip.get("type").getAsString());
        assertEquals("24", feip.get("sn").getAsString());
        assertEquals("Image", feip.get("name").getAsString());
        JsonObject data = feip.getAsJsonObject("data");
        assertEquals("publish", data.get("op").getAsString());
        assertEquals(DID, data.get("did").getAsString());
        assertEquals("image/png", data.get("format").getAsString());
        assertEquals("(sid)" + ID, data.getAsJsonArray("locas").get(0).getAsString());
        assertFalse(data.has("imageId"));
        assertFalse(data.has("lang"));
        assertFalse(data.has("summary"));
        assertFalse(data.has("authors"));
    }

    @Test
    public void updateNamesTheImageAndCarriesEveryField() {
        List<String> authors = Arrays.asList("A", "B");
        JsonObject data = parse(ImageManager.updateJson(ID, "Logo 2", DID, "en", authors,
                "image/webp", "new", Collections.singletonList("(sid)x"))).getAsJsonObject("data");
        assertEquals("update", data.get("op").getAsString());
        assertEquals(ID, data.get("imageId").getAsString());
        assertEquals("Logo 2", data.get("title").getAsString());
        assertEquals(DID, data.get("did").getAsString());
        assertEquals("en", data.get("lang").getAsString());
        assertEquals(2, data.getAsJsonArray("authors").size());
        assertEquals("new", data.get("summary").getAsString());
    }

    @Test
    public void deleteAndRecoverTakeAList() {
        JsonObject delete = parse(ImageManager.deleteJson(Collections.singletonList(ID))).getAsJsonObject("data");
        assertEquals("delete", delete.get("op").getAsString());
        assertEquals(ID, delete.getAsJsonArray("imageIds").get(0).getAsString());
        JsonObject recover = parse(ImageManager.recoverJson(Collections.singletonList(ID))).getAsJsonObject("data");
        assertEquals("recover", recover.get("op").getAsString());
    }

    @Test
    public void splitListTrimsAndDropsRepeats() {
        assertEquals(Arrays.asList("A", "B", "C"), ImageManager.splitList(" A, B，A\nC ,"));
    }

    @Test
    public void opReturnLimitIsMeasuredInBytes() {
        assertTrue(ImageManager.fitsOpReturn(ImageManager.publishJson("t", DID, null, null, null, "x", null)));
        String big = "图".repeat(1400); // 4200 bytes in UTF-8, 1400 chars
        assertFalse(ImageManager.fitsOpReturn(ImageManager.publishJson("t", DID, null, null, null, big, null)));
    }
}
