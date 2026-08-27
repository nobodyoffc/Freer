package com.fc.fc_ajdk.fudp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.fudp.message.MessageCodec;
import com.fc.fc_ajdk.fudp.message.MessageType;
import com.fc.fc_ajdk.fudp.message.ResponseMessage;
import com.fc.fc_ajdk.fudp.node.AssembledMessage;
import com.fc.fc_ajdk.fudp.node.MessageFrameAssembler;
import com.fc.fc_ajdk.fudp.util.Varint;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Validates the streaming-receive path off-device: assembler spill-to-disk, the
 * file-backed {@link ResponseMessage} decode/offset math, and byte-exact recovery of
 * a large binary payload — the parts that cannot be exercised by a single-process run.
 */
public class StreamingReceiveTest {

    private static final int FIXED_HEADER = 10; // type(1)+messageId(8)+flags(1)

    /** Build a UnifiedCodec response data region: [headerLen(4)][json][binary]. */
    private static byte[] unifiedData(String json, byte[] binary) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + j.length + binary.length);
        bb.putInt(j.length);
        bb.put(j);
        bb.put(binary);
        return bb.array();
    }

    /** Feed wire bytes to an assembler in fixed-size chunks and collect all messages. */
    private static List<AssembledMessage> feed(MessageFrameAssembler a, byte[] wire, int chunk) {
        List<AssembledMessage> out = new ArrayList<>();
        for (int off = 0; off < wire.length; off += chunk) {
            int len = Math.min(chunk, wire.length - off);
            byte[] c = new byte[len];
            System.arraycopy(wire, off, c, 0, len);
            a.addData(c);
            out.addAll(a.extractMessages());
        }
        return out;
    }

    /** Mirror of FudpNode.handleIncomingFileBacked's header parse; returns [dataOffset,dataLength,status]. */
    private static long[] decodeResponseHeader(AssembledMessage msg) throws IOException {
        int headBytes = (int) Math.min(msg.length(), FIXED_HEADER + 10L + 2L);
        byte[] header = msg.readHeader(headBytes);
        assertEquals(MessageType.RESPONSE, MessageCodec.peekType(header));
        Varint.DecodeResult vr = Varint.decode(header, FIXED_HEADER);
        long payloadLength = vr.value;
        int payloadOffset = FIXED_HEADER + vr.bytesConsumed;
        int status = ((header[payloadOffset] & 0xFF) << 8) | (header[payloadOffset + 1] & 0xFF);
        long dataOffset = payloadOffset + 2L;
        long dataLength = payloadLength - 2;
        return new long[]{dataOffset, dataLength, status, MessageCodec.peekMessageId(header)};
    }

    /** Stream the binary out of a file-backed response exactly as FapiClient does. */
    private static byte[] streamBinary(ResponseMessage r, String[] jsonOut) throws IOException {
        long dataLen = r.dataLength();
        try (DataInputStream dis = new DataInputStream(r.openData())) {
            int headerLen = dis.readInt();
            assertTrue(headerLen >= 0 && headerLen <= dataLen - 4);
            byte[] jb = new byte[headerLen];
            dis.readFully(jb);
            jsonOut[0] = new String(jb, StandardCharsets.UTF_8);
            long binaryLen = dataLen - 4 - headerLen;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long copied = 0;
            int n;
            while (copied < binaryLen
                    && (n = dis.read(buf, 0, (int) Math.min(buf.length, binaryLen - copied))) > 0) {
                bos.write(buf, 0, n);
                copied += n;
            }
            return bos.toByteArray();
        }
    }

    @Test
    public void largeResponseSpillsAndRecoversBinaryExactly() throws Exception {
        byte[] binary = new byte[200_000];
        new Random(42).nextBytes(binary);
        String json = "{\"code\":0,\"data\":\"meta\"}";
        byte[] data = unifiedData(json, binary);

        long msgId = 0x0123456789ABCDEFL;
        byte[] wire = MessageCodec.encode(new ResponseMessage(msgId, 0, data));

        File tmp = new File(System.getProperty("java.io.tmpdir"), "fudp-test-" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        // spillThreshold well below the message size forces the disk path.
        MessageFrameAssembler a = new MessageFrameAssembler(64L * 1024 * 1024, 4096, tmp);

        List<AssembledMessage> msgs = feed(a, wire, 7000);
        assertEquals(1, msgs.size());
        AssembledMessage m = msgs.get(0);
        assertTrue("expected spill to disk", m.isFileBacked());
        assertEquals(wire.length, m.length());

        long[] h = decodeResponseHeader(m);
        assertEquals(0, h[2]);          // statusCode
        assertEquals(msgId, h[3]);      // messageId
        assertEquals(data.length, h[1]); // data region length

        ResponseMessage r = new ResponseMessage();
        r.setFileBackedData(m.file(), h[0], h[1]);
        assertTrue(r.isFileBacked());

        String[] jsonOut = new String[1];
        byte[] recovered = streamBinary(r, jsonOut);
        assertEquals(json, jsonOut[0]);
        assertArrayEquals(binary, recovered);

        r.deleteBackingFile();
        assertFalse(m.file().exists());
    }

    @Test
    public void smallResponseStaysInRamAndDecodesNormally() throws Exception {
        byte[] binary = new byte[500];
        new Random(7).nextBytes(binary);
        byte[] data = unifiedData("{\"code\":0}", binary);
        byte[] wire = MessageCodec.encode(new ResponseMessage(99L, 0, data));

        // spillThreshold above the message → RAM path.
        MessageFrameAssembler a = new MessageFrameAssembler(64L * 1024 * 1024, 1024 * 1024, null);
        List<AssembledMessage> msgs = feed(a, wire, 128);
        assertEquals(1, msgs.size());
        AssembledMessage m = msgs.get(0);
        assertFalse(m.isFileBacked());

        ResponseMessage r = (ResponseMessage) MessageCodec.decode(m.bytes());
        assertEquals(0, r.getStatusCode());
        assertEquals(99L, r.getMessageId());
        assertArrayEquals(data, r.getData());
    }

    @Test
    public void twoMessagesInOneBufferBothExtracted() {
        byte[] w1 = MessageCodec.encode(new ResponseMessage(1L, 0, new byte[]{1, 2, 3}));
        byte[] w2 = MessageCodec.encode(new ResponseMessage(2L, 0, new byte[]{4, 5}));
        byte[] both = new byte[w1.length + w2.length];
        System.arraycopy(w1, 0, both, 0, w1.length);
        System.arraycopy(w2, 0, both, w1.length, w2.length);

        MessageFrameAssembler a = new MessageFrameAssembler();
        a.addData(both);
        List<AssembledMessage> msgs = a.extractMessages();
        assertEquals(2, msgs.size());
        assertEquals(1L, ((ResponseMessage) MessageCodec.decode(msgs.get(0).bytes())).getMessageId());
        assertEquals(2L, ((ResponseMessage) MessageCodec.decode(msgs.get(1).bytes())).getMessageId());
    }
}
