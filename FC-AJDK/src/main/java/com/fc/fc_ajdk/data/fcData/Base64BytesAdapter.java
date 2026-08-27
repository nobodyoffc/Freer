package com.fc.fc_ajdk.data.fcData;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Base64;

/**
 * Reads and writes a {@code byte[]} as a Base64 <em>string</em>.
 *
 * <p>Gson's default for a byte array is a JSON array of signed numbers --
 * {@code [72,101,-127]} -- which is four times the size of the Base64 form and
 * is not what the Mac client writes or reads. FIMP0V2 s7 pins the two binary
 * fields of {@code ImMessage} to Base64 strings for exactly that reason: a
 * history file is written by one client and read by the other, so the local
 * storage format is an interoperation format in its own right.
 *
 * <p>Applied per field with {@code @JsonAdapter} rather than registered
 * globally, because Gson instances are built ad hoc all over this codebase and
 * a global {@code byte[]} adapter would silently change every other class that
 * carries one.
 */
public class Base64BytesAdapter extends TypeAdapter<byte[]> {

    @Override
    public void write(JsonWriter out, byte[] value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        out.value(Base64.getEncoder().encodeToString(value));
    }

    @Override
    public byte[] read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        String encoded = in.nextString();
        if (encoded.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            // A v1 record would land here: `cipher` held a JSON envelope and
            // `dataBase64` a Base64 string under a different key. Failing at
            // the field is the point of the rename -- see FIMP0V2 s7.
            throw new IOException("Expected Base64 for a binary field: " + e.getMessage(), e);
        }
    }
}
