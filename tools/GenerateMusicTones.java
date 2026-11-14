import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** No external audio: 20-second sine waves, 8 kHz mono PCM16, 320044 bytes each.
 * From the repo root: java tools/GenerateMusicTones.java
 */
class GenerateMusicTones {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of("app/src/debug/assets/music");
        Files.createDirectories(directory);
        write(directory.resolve("tone_a.wav"), 440);
        write(directory.resolve("tone_b.wav"), 660);
    }

    private static void write(Path path, int frequency) throws Exception {
        int rate = 8000;
        int count = rate * 20;
        ByteBuffer bytes = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(36 + count * 2);
        bytes.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(16).putShort((short) 1).putShort((short) 1);
        bytes.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        bytes.put("data".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(count * 2);
        for (int index = 0; index < count; index++) {
            double fade = Math.min(1.0, Math.min(index, count - 1 - index) / 160.0);
            bytes.putShort((short) Math.round(Math.sin(2 * Math.PI * frequency * index / rate) * 2800 * fade));
        }
        Files.write(path, bytes.array());
        System.out.println(path + " (20 s, " + frequency + " Hz, " + bytes.capacity() + " bytes)");
    }
}
