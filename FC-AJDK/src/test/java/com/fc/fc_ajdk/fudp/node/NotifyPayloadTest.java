package com.fc.fc_ajdk.fudp.node;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.fudp.message.NotifyMessage;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Random;

/**
 * NotifyPayload is what a listener implementing
 * {@link NodeEventListener#onNotifyStream} is handed, so it must read the same way
 * whether the notify stayed in memory or was spilled to disk during reassembly —
 * and, for the file-backed case, must read exactly the payload's region of the
 * spill file rather than the framing around it.
 */
public class NotifyPayloadTest {

    private static byte[] drain(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    @Test
    public void streamsExactlyThePayloadRegionOfASpillFile() throws Exception {
        byte[] payload = new byte[300_000];
        new Random(5).nextBytes(payload);

        // Frame the payload the way a spill file holds it: leading bytes that are not
        // part of the data, then the data, then trailing bytes that are not either.
        byte[] lead = "....framing-before....".getBytes();
        byte[] trail = "....framing-after....".getBytes();
        File spill = File.createTempFile("notify-payload-", ".spill");
        try (FileOutputStream out = new FileOutputStream(spill)) {
            out.write(lead);
            out.write(payload);
            out.write(trail);
        }

        NotifyMessage msg = new NotifyMessage();
        msg.setDataType(NotifyMessage.DATA_TYPE_JSON);
        msg.setFileBackedData(spill, lead.length, payload.length);

        NotifyPayload p = new NotifyPayload(msg);
        assertTrue("payload should report itself file-backed", p.isFileBacked());
        assertEquals(payload.length, p.length());

        try (InputStream in = p.open()) {
            assertArrayEquals("stream must yield the data region only", payload, drain(in));
        }
        // readAll() is the same bytes, just materialised.
        assertArrayEquals(payload, p.readAll());

        //noinspection ResultOfMethodCallIgnored
        spill.delete();
    }

    @Test
    public void readsTheSameWayForAnInMemoryNotify() throws Exception {
        byte[] payload = new byte[1024];
        new Random(11).nextBytes(payload);

        NotifyPayload p = new NotifyPayload(new NotifyMessage(payload, NotifyMessage.DATA_TYPE_RAW));
        assertFalse("an in-memory notify is not file-backed", p.isFileBacked());
        assertEquals(payload.length, p.length());

        try (InputStream in = p.open()) {
            assertArrayEquals(payload, drain(in));
        }
        assertArrayEquals(payload, p.readAll());
    }

    @Test
    public void listenerOptsOutOfStreamingByDefault() {
        NodeEventListener plain = new NodeEventListener() {};
        assertFalse("a listener must not be treated as stream-capable unless it says so",
                plain.handlesNotifyStream());

        NodeEventListener streaming = new NodeEventListener() {
            @Override
            public boolean handlesNotifyStream() {
                return true;
            }
        };
        assertTrue(streaming.handlesNotifyStream());
    }
}
