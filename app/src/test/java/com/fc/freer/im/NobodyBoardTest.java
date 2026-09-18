package com.fc.freer.im;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImMessageBody;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.utils.BytesUtils;

import org.junit.Test;

/**
 * The board is only readable because the nobody's private key is public, so
 * these pin the two facts that make it work: the published key really is the
 * board FID's, and a post sealed to the board on the way out opens again on the
 * way in. A reader that skips the opening step sees an empty board.
 */
public class NobodyBoardTest {

    /** An arbitrary requester key; the board never learns who this is beforehand. */
    private static byte[] requesterPrikey() {
        byte[] prikey = new byte[32];
        for (int i = 0; i < prikey.length; i++) prikey[i] = (byte) (i + 1);
        return prikey;
    }

    @Test
    public void publishedPrikeyIsTheBoardFid() {
        String fid = KeyTools.prikeyToFid(
                BytesUtils.hexToByteArray(NobodyBoard.DEFAULT_NOBODY_PRIKEY));
        assertEquals(NobodyBoard.DEFAULT_NOBODY_FID, fid);
    }

    @Test
    public void sealedPostIsOpenedAndParsed() {
        byte[] senderPrikey = requesterPrikey();
        String senderFid = KeyTools.prikeyToFid(senderPrikey);
        byte[] boardPubkey = KeyTools.prikeyToPubkey(
                BytesUtils.hexToByteArray(NobodyBoard.DEFAULT_NOBODY_PRIKEY));

        String content = NobodyBoard.buildFirstFchRequest(senderFid, "just installed");
        ImMessage post = ImMessage.createText(
                ImType.P2P, senderFid, NobodyBoard.DEFAULT_NOBODY_FID, content);
        post.setIdFromLong(1L);
        // Exactly what P2pHandler puts on the wire for anything routed through a
        // third party — here, the board's DOCK server.
        assertTrue(ImMessageBody.sealForPeer(post, senderPrikey, boardPubkey, false));
        byte[] wire = post.toWireBytes(senderPrikey);

        assertTrue("a fetched post arrives sealed",
                ImMessage.fromWireBytes(wire).isSealed());

        ImMessage opened = NobodyBoard.openBoardMessage(wire);
        assertNotNull(opened);
        NobodyBoard.Request request = NobodyBoard.parseFirstFchRequest(
                opened.getContent(), 1700000000000L);
        assertNotNull("template must match after opening", request);
        assertEquals(senderFid, request.requesterFid);
        assertEquals("just installed", request.note);
    }

    @Test
    public void unsealedPostStillReads() {
        String senderFid = KeyTools.prikeyToFid(requesterPrikey());
        ImMessage post = ImMessage.createText(ImType.P2P, senderFid,
                NobodyBoard.DEFAULT_NOBODY_FID,
                NobodyBoard.buildFirstFchRequest(senderFid, ""));
        post.setIdFromLong(2L);

        ImMessage opened = NobodyBoard.openBoardMessage(post.toWireBytes(requesterPrikey()));
        assertNotNull(opened);
        assertNotNull(NobodyBoard.parseFirstFchRequest(opened.getContent(), 0L));
    }

    @Test
    public void garbageIsRejectedNotThrown() {
        assertNull(NobodyBoard.openBoardMessage(null));
        assertNull(NobodyBoard.openBoardMessage(new byte[0]));
        assertNull(NobodyBoard.openBoardMessage(new byte[]{1, 2, 3, 4, 5}));
    }
}
