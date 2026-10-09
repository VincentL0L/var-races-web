import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * Pixel art for the items (see ItemSystem): the item box on the track, an icon for each
 * item (shown in the HUD), and the things you see on the track (rocket, oil, shield bubble).
 *
 * Run from the project folder:  java tools/MakeItems.java   ->  assets/ui/items/*.png
 */
public class MakeItems {
    public static void main(String[] args) throws Exception {
        new File("assets/ui/items").mkdirs();
        // k outline, y gold, Y light gold, o dark gold, w white
        save("box", "kyYowr", new int[] {0x2a1d17, 0xf2b83a, 0xffe08a, 0xb57a1c, 0xffffff, 0xd9433b},
            "..kkkkkkkkkkkk..",
            ".kYYYYYYYYYYYYk.",
            "kYyyyyyyyyyyyyok",
            "kYyyyywwwwyyyyok",
            "kYyyywwyywwyyyok",
            "kYyyyyyyywwyyyok",
            "kYyyyyyywwyyyyok",
            "kYyyyyyywwyyyyok",
            "kYyyyyyyyyyyyyok",
            "kYyyyyyywwyyyyok",
            "kYyyyyyywwyyyyok",
            "kYyyyyyyyyyyyyok",
            "kYyyyyyyyyyyyyok",
            "kYoooooooooooook",
            ".kooooooooooook.",
            "..kkkkkkkkkkkk..");
        // nitro: a blue canister with a flame (k, b blue, B light blue, w white, r red, o orange, y yellow)
        save("nitro", "kbBwroy", new int[] {0x1c1c22, 0x2f6fd0, 0x7fb6ff, 0xffffff, 0xd9433b, 0xf08a2e, 0xffe066},
            "......kkkk......",
            ".....kwwwwk.....",
            "....kkbbbbkk....",
            "...kBbbbbbbbk...",
            "...kBbwwwwbbk...",
            "...kBbwbbwbbk...",
            "...kBbwwwwbbk...",
            "...kBbbbbbbbk...",
            "...kBbbbbbbbk...",
            "...kBbwbbwbbk...",
            "...kBbbbbbbbk...",
            "...kkbbbbbbkk...",
            "....kkkkkkkk....",
            ".....yooooy.....",
            "......orro......",
            ".......rr.......");
        // bottle rocket: red body, white tip, stick (k, r red, R light red, w white, s stick, y spark)
        save("rocket", "krRwsy", new int[] {0x1c1c22, 0xd9433b, 0xff8a7a, 0xffffff, 0x8a5a36, 0xffe066},
            ".......kk.......",
            "......kwwk......",
            ".....kwwwwk.....",
            ".....kRrrrk.....",
            ".....kRrrrk.....",
            ".....kRwwrk.....",
            ".....kRrrrk.....",
            ".....kRrrrk.....",
            ".....kRwwrk.....",
            ".....kRrrrk.....",
            "....kkkrrkkk....",
            "...kr.ks.k.rk...",
            ".......s........",
            ".......s........",
            "......ysy.......",
            ".....y.y.y......");
        // oil slick: a black puddle with a rainbow sheen
        save("oil", "kdgpcy", new int[] {0x0e0e12, 0x24242c, 0x3a3a48, 0xb57cff, 0x3fe0ff, 0xffe066},
            "................",
            "....kkkkkk......",
            "..kkddddddkk....",
            ".kdddggdddddkk..",
            ".kddgppgdddddk..",
            "kdddgccgddgddk..",
            "kdddddyddgpgdk..",
            "kddddddddgcgddk.",
            ".kdddggddddddddk",
            ".kddgpcgdddddddk",
            "..kddgyddddddkk.",
            "...kkddddddkk...",
            ".....kkkkkk.....",
            "................",
            "................",
            "................");
        // bubble shield: a pale blue bubble with a shine
        save("bubble", "kbBw", new int[] {0x1f4f7f, 0x7fc8ff, 0xc8ecff, 0xffffff},
            ".....kkkkkk.....",
            "...kkBBBBBBkk...",
            "..kBwwBBBBBBBk..",
            ".kBwwBBBBBBBBBk.",
            ".kBwBBBBBBBBBBk.",
            "kBBBBBBBBBBBBBbk",
            "kBBBBBBBBBBBBBbk",
            "kBBBBBBBBBBBBBbk",
            "kBBBBBBBBBBBBBbk",
            "kBBBBBBBBBBBBbbk",
            "kBBBBBBBBBBBBbbk",
            ".kBBBBBBBBBBbbk.",
            ".kbBBBBBBBBbbbk.",
            "..kbbBBBBbbbbk..",
            "...kkbbbbbbkk...",
            ".....kkkkkk.....");
        // static pulse: a yellow lightning bolt in rings
        save("pulse", "kyYc", new int[] {0x1c1c22, 0xffd23f, 0xfff4a8, 0x3fe0ff},
            "...cc......cc...",
            "..c..........c..",
            ".c.....kkkk...c.",
            "c.....kYyyk....c",
            "c....kYyyk.....c",
            ".....kYyyk......",
            "....kYyyykkkk...",
            "....kYyyyyyyk...",
            "...kkkkYyyyk....",
            "......kYyyk.....",
            "c....kYyyk.....c",
            "c....kYyk......c",
            ".c...kYk......c.",
            "..c..kk......c..",
            "...cc......cc...",
            "................");
        // frost blaster: an ice crystal (k dark blue, b blue, B light blue, w white)
        save("frost", "kbBw", new int[] {0x1f3f7f, 0x5fb0f0, 0xb8e6ff, 0xffffff},
            ".......kk.......",
            "......kwBk......",
            "..kk..kBbk..kk..",
            "..kBk.kBbk.kBk..",
            "...kBkkBbkkBk...",
            "....kBBwBBBk....",
            "kkkkkBwwwBbkkkkk",
            "kBBBBwwwwwBBBBbk",
            "kkkkkBwwwBbkkkkk",
            "....kBBwBBBk....",
            "...kBkkBbkkBk...",
            "..kBk.kBbk.kBk..",
            "..kk..kBbk..kk..",
            "......kBbk......",
            ".......kk.......",
            "................");
        // fire blaster: a fireball (k dark red, r red, o orange, y yellow, w white core)
        save("fire", "kroyw", new int[] {0x5a1408, 0xd9332a, 0xf08a2e, 0xffd23f, 0xfff6d0},
            "......k.........",
            ".....kr...k.....",
            "....kro..kr.....",
            "...kroo.kro.k...",
            "...kroookrookr..",
            "..kroooooooookr.",
            "..krooyyyyoook..",
            ".krooyyyyyyook..",
            ".kroyyywwyyyok..",
            ".kroyywwwwyyok..",
            ".kroyywwwwyyok..",
            ".krooyywwyyook..",
            "..krooyyyyook...",
            "...krroooork....",
            "....kkrrrrk.....",
            "......kkkk......");
        // the shield ring drawn around a protected car (32 x 32)
        BufferedImage ring = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                double d = Math.hypot(x - 15.5, y - 15.5);
                if (d < 15.5) {
                    int alpha = d > 13.5 ? 220 : (int) (60 * d / 13.5);
                    int c = d > 13.5 ? 0x7fc8ff : 0xc8ecff;
                    if (x < 12 && y < 12 && d > 9 && d < 12) {
                        alpha = 200;
                        c = 0xffffff;
                    }
                    ring.setRGB(x, y, alpha << 24 | c);
                }
            }
        }
        ImageIO.write(ring, "png", new File("assets/ui/items/shield.png"));
        System.out.println("items drawn");
    }

    static void save(String name, String letters, int[] colors, String... rows) throws Exception {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            String row = rows[y];
            for (int x = 0; x < 16 && x < row.length(); x++) {
                int i = letters.indexOf(row.charAt(x));
                img.setRGB(x, y, i < 0 ? 0 : 0xff000000 | colors[i]);
            }
        }
        ImageIO.write(img, "png", new File("assets/ui/items/" + name + ".png"));
    }
}
