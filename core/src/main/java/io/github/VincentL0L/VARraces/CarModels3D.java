package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * The 3D shape of each car in the game, for the first-person view, worked out from its
 * top-down sprite: the garage's Sprinter (hatchback), Roadster (open top), GT (low, with a
 * wing), Muscle (wide, bonnet scoop) and Hauler (pickup), and the cars each city sends
 * out: Waymo (with its spinning roof sensor), kei van, taxi, police car, double-decker
 * bus, 2CV, scooter with rider and moon rover.
 *
 * Every shape is drawn in the car's own frame: x across, y toward the nose, z up, with the
 * car's sprite w wide and h long. The sprite itself is laid over the top of the body
 * (the bonnet and boot), so the paint job, stripes and markings match the overhead view.
 */
public final class CarModels3D {
    /** how a kind of car is built */
    static final class Shape {
        /** body width as a share of the sprite's, and where the body starts off the ground */
        float width = 1f, low = 0.9f;
        /** the top of the bonnet/boot (where the sprite is laid) */
        float deck = 3.6f;
        /** the cabin, as shares of the length from the tail (0) to the nose (1), and its roof height */
        float cabinFrom = 0.26f, cabinTo = 0.7f, cabinTop = 5.8f;
        boolean roof = true;
        /** wheel radius-ish (height), and how far in from each end the axles are (share of length) */
        float wheel = 2.6f, axle = 0.21f;
        /** extras: SPOILER, SCOOP, BED, SENSOR, TAXI, SIREN, BUS, BIKE, ROVER, OPEN */
        String extra = "";

        Shape cabin(float from, float to, float top) {
            cabinFrom = from;
            cabinTo = to;
            cabinTop = top;
            return this;
        }

        Shape deck(float d) {
            deck = d;
            return this;
        }

        Shape extra(String e) {
            extra = e;
            return this;
        }

        Shape wheels(float size, float axleIn) {
            wheel = size;
            axle = axleIn;
            return this;
        }

        Shape width(float wd) {
            width = wd;
            return this;
        }

        boolean has(String e) {
            return extra.contains(e);
        }
    }

    private CarModels3D() {}

    /**
     * @param art a car's sprite
     * @return the shape of that car (a plain coupe for anything unknown)
     */
    static Shape shapeOf(Texture art) {
        String name = "";
        if (art != null && art.getTextureData() instanceof FileTextureData) {
            name = ((FileTextureData) art.getTextureData()).getFileHandle().nameWithoutExtension();
            int us = name.indexOf('_');
            if (us > 0) {
                name = name.substring(0, us);     // gt_2 -> gt
            }
        }
        switch (name) {
            case "sprinter":   // little hatchback: the cabin runs nearly to the tail
                return new Shape().width(0.9f).cabin(0.12f, 0.66f, 6f).extra("SPOILER_SMALL");
            case "roadster":   // open two-seater: windscreen, seats, roll hoops
                return new Shape().cabin(0.3f, 0.62f, 5.2f).extra("OPEN");
            case "gt":         // long and low, cabin set back, big wing
                return new Shape().deck(3.1f).cabin(0.24f, 0.6f, 5f).extra("SPOILER");
            case "muscle":     // wide, bulging bonnet scoop, ducktail
                return new Shape().deck(3.8f).cabin(0.22f, 0.6f, 5.9f).extra("SCOOP SPOILER_SMALL");
            case "hauler":     // pickup: tall cab up front, open bed behind
                return new Shape().deck(4.6f).cabin(0.42f, 0.72f, 8.2f).wheels(3.4f, 0.2f).extra("BED");
            case "waymo":      // tall electric SUV with the sensor dome on the roof
                return new Shape().deck(4.4f).cabin(0.18f, 0.72f, 7.6f).wheels(3f, 0.2f).extra("SENSOR");
            case "kei":        // boxy kei van: almost all cabin
                return new Shape().width(0.92f).deck(3.6f).cabin(0.08f, 0.86f, 8.8f).wheels(2.4f, 0.16f);
            case "cab":
                return new Shape().cabin(0.26f, 0.68f, 6.2f).extra("TAXI");
            case "cop":
                return new Shape().cabin(0.26f, 0.68f, 6.2f).extra("SIREN");
            case "bus":        // red double-decker
                return new Shape().deck(19f).cabin(0f, 0f, 19f).wheels(3.6f, 0.17f).extra("BUS");
            case "2cv":        // round little 2CV: tall cabin, narrow body
                return new Shape().width(0.88f).deck(3.4f).cabin(0.2f, 0.66f, 7f).wheels(3f, 0.2f);
            case "moto":       // scooter and rider
                return new Shape().width(0.35f).deck(3.2f).cabin(0f, 0f, 0f).wheels(3f, 0.12f).extra("BIKE");
            case "f1":         // open-wheel racer: low nose, wings front and back, cockpit and halo
                return new Shape().width(0.6f).deck(2.4f).cabin(0f, 0f, 0f).wheels(3.2f, 0.16f).extra("F1");
            case "rover":      // moon buggy: open frame, six big wheels, gold dish
                return new Shape().deck(4.2f).cabin(0f, 0f, 0f).wheels(3.6f, 0.14f).extra("ROVER");
            default:           // the classic racers (car1-6): a sports coupe
                return new Shape().cabin(0.26f, 0.64f, 5.6f).extra("SPOILER_SMALL");
        }
    }

    /** the eye height in a car of this shape (just over its roof, or over the rider's head) */
    static float eyeHeight(Shape s) {
        if (s.has("BUS")) {
            return 15f;                       // the top deck's front seats
        }
        if (s.has("BIKE")) {
            return 8.5f;
        }
        if (s.has("ROVER") || s.has("OPEN")) {
            return s.deck + 3f;
        }
        return s.cabinTop + 0.5f;
    }

    /**
     * draws the body (not the sprite on top); the shape renderer is begun, with the car's
     * transform set
     * @param body paint colour   @param inside our own car: skip what would block the view
     */
    static void draw(ShapeRenderer sr, Shape s, float w, float h, Color body, boolean inside, float clock) {
        float bw = w * s.width;
        float tail = -h / 2f;
        if (!inside) {
            // shadow
            sr.setColor(0f, 0f, 0f, 0.35f);
            sr.rect(-bw / 2f - 1f, -h / 2f - 1f, bw + 2f, h + 2f);
            wheels(sr, s, bw, h);
        }
        if (s.has("BIKE")) {
            bike(sr, s, w, h, body, inside);
            return;
        }
        if (s.has("F1")) {
            formula(sr, s, w, h, body, inside);
            return;
        }
        if (inside && s.has("BUS")) {
            return;     // we're up on the top deck: the bus is all round us, not in front
        }
        // lower body: dark sill, then the paint
        sr.setColor(body.r * 0.45f, body.g * 0.45f, body.b * 0.45f, 1f);
        box(sr, -bw / 2f, tail, s.low, bw, h, Math.min(1.2f, s.deck - s.low));
        sr.setColor(body.r * 0.85f, body.g * 0.85f, body.b * 0.85f, 1f);
        if (s.has("BUS")) {
            bus(sr, s, bw, h, body, inside);
            return;
        }
        box(sr, -bw / 2f, tail, s.low + 1.2f, bw, h, s.deck - s.low - 1.2f);
        if (s.has("ROVER")) {
            rover(sr, s, bw, h, clock);
            return;
        }
        float cb = tail + h * s.cabinFrom, cl = h * (s.cabinTo - s.cabinFrom);
        if (!inside && cl > 0f) {
            if (s.has("OPEN")) {
                // windscreen, two seats and a pair of roll hoops
                sr.setColor(0.55f, 0.7f, 0.8f, 1f);
                box(sr, -bw / 2f + 1f, cb + cl - 0.6f, s.deck, bw - 2f, 0.5f, s.cabinTop - s.deck);
                sr.setColor(0.12f, 0.1f, 0.09f, 1f);
                box(sr, -bw / 2f + 1.2f, cb + 0.5f, s.deck - 0.6f, bw / 2f - 1.6f, cl - 2f, 1.6f);
                box(sr, 0.4f, cb + 0.5f, s.deck - 0.6f, bw / 2f - 1.6f, cl - 2f, 1.6f);
                sr.setColor(0.7f, 0.7f, 0.72f, 1f);
                box(sr, -bw / 2f + 1.5f, cb + 0.2f, s.deck, 0.5f, 0.5f, 2.2f);
                box(sr, bw / 2f - 2f, cb + 0.2f, s.deck, 0.5f, 0.5f, 2.2f);
            } else {
                // glass all round, then a roof in the body colour
                sr.setColor(0.13f, 0.17f, 0.24f, 1f);
                box(sr, -bw / 2f + 1.1f, cb, bw - 2.2f, cl, s.deck, s.cabinTop - s.deck - 0.45f, true);
                sr.setColor(body);
                box(sr, -bw / 2f + 1.4f, cb + 0.9f, s.cabinTop - 0.45f, bw - 2.8f, cl - 1.8f, 0.5f);
            }
        }
        if (s.has("SCOOP")) {
            sr.setColor(body.r * 0.25f, body.g * 0.25f, body.b * 0.25f, 1f);
            box(sr, -1.4f, h * 0.18f, s.deck, 2.8f, 3.2f, 0.9f);
        }
        if (!inside) {
            if (s.has("SPOILER_SMALL")) {
                sr.setColor(body.r * 0.6f, body.g * 0.6f, body.b * 0.6f, 1f);
                box(sr, -bw / 2f + 0.5f, tail + 0.3f, s.deck, bw - 1f, 1.2f, 0.7f);
            } else if (s.has("SPOILER")) {
                sr.setColor(0.1f, 0.1f, 0.11f, 1f);
                box(sr, -bw / 2f + 1.5f, tail + 1f, s.deck, 0.5f, 0.6f, 2f);
                box(sr, bw / 2f - 2f, tail + 1f, s.deck, 0.5f, 0.6f, 2f);
                sr.setColor(body.r * 0.7f, body.g * 0.7f, body.b * 0.7f, 1f);
                box(sr, -bw / 2f - 0.3f, tail + 0.2f, s.deck + 2f, bw + 0.6f, 2.4f, 0.45f);
            }
            if (s.has("BED")) {
                // the pickup's bed: a dark floor with the body for walls (the sprite shows planks)
                sr.setColor(body.r * 0.6f, body.g * 0.6f, body.b * 0.6f, 1f);
                box(sr, -bw / 2f, tail, s.deck, 0.7f, cb - tail, 1.6f);
                box(sr, bw / 2f - 0.7f, tail, s.deck, 0.7f, cb - tail, 1.6f);
                box(sr, -bw / 2f, tail, s.deck, bw, 0.7f, 1.6f);
            }
            if (s.has("SENSOR")) {
                // the lidar dome, spinning, and little cameras on the corners
                sr.setColor(0.15f, 0.15f, 0.17f, 1f);
                box(sr, -1.6f, cb + cl * 0.45f, s.cabinTop + 0.05f, 3.2f, 3.2f, 1.3f);
                float spin = (clock * 4f) % 1f;
                sr.setColor(0.25f + spin * 0.3f, 0.85f, 0.8f, 1f);
                box(sr, -1.2f, cb + cl * 0.45f + 0.4f, s.cabinTop + 1.35f, 2.4f, 2.4f, 0.7f);
                sr.setColor(0.2f, 0.2f, 0.22f, 1f);
                box(sr, -bw / 2f - 0.4f, h / 2f - 4f, s.deck - 0.6f, 0.6f, 1.2f, 1.2f);
                box(sr, bw / 2f - 0.2f, h / 2f - 4f, s.deck - 0.6f, 0.6f, 1.2f, 1.2f);
            }
            if (s.has("TAXI")) {
                sr.setColor(1f, 0.95f, 0.75f, 1f);
                box(sr, -1.8f, cb + cl / 2f - 0.8f, s.cabinTop + 0.05f, 3.6f, 1.6f, 1.1f);
            }
            if (s.has("SIREN")) {
                // the light bar flashes red, then blue
                boolean red = ((int) (clock * 6f)) % 2 == 0;
                sr.setColor(red ? 1f : 0.35f, 0.12f, red ? 0.12f : 0.25f, 1f);
                box(sr, -bw / 2f + 1.6f, cb + cl / 2f - 0.8f, s.cabinTop + 0.05f, bw / 2f - 1.6f, 1.6f, 0.9f);
                sr.setColor(0.15f, red ? 0.25f : 0.45f, red ? 0.4f : 1f, 1f);
                box(sr, 0f, cb + cl / 2f - 0.8f, s.cabinTop + 0.05f, bw / 2f - 1.6f, 1.6f, 0.9f);
            }
            lights(sr, bw, h, s.low + 1.3f);
        }
    }

    private static void wheels(ShapeRenderer sr, Shape s, float bw, float h) {
        int axles = s.has("ROVER") ? 3 : 2;
        for (int a = 0; a < axles; a++) {
            float wy = axles == 3 ? (a - 1) * (h / 2f - h * s.axle) : (a == 0 ? -1 : 1) * (h / 2f - h * s.axle);
            if (s.has("BIKE")) {
                sr.setColor(0.07f, 0.07f, 0.08f, 1f);
                box(sr, -0.6f, wy - s.wheel / 2f, 0f, 1.2f, s.wheel, s.wheel);
                continue;
            }
            for (int side = -1; side <= 1; side += 2) {
                float wx = side * (bw / 2f - 0.6f);
                sr.setColor(0.07f, 0.07f, 0.08f, 1f);
                box(sr, wx - 1.1f, wy - s.wheel * 0.85f, 0f, 2.2f, s.wheel * 1.7f, s.wheel);
                sr.setColor(0.6f, 0.6f, 0.62f, 1f);
                box(sr, wx + side * 1.15f - 0.05f, wy - s.wheel * 0.3f, s.wheel * 0.2f, 0.1f, s.wheel * 0.6f, s.wheel * 0.6f);
            }
        }
    }

    private static void lights(ShapeRenderer sr, float bw, float h, float z) {
        sr.setColor(1f, 0.95f, 0.72f, 1f);
        box(sr, -bw / 2f + 0.8f, h / 2f - 0.2f, z, 2.2f, 0.4f, 0.9f);
        box(sr, bw / 2f - 3f, h / 2f - 0.2f, z, 2.2f, 0.4f, 0.9f);
        sr.setColor(0.9f, 0.08f, 0.08f, 1f);
        box(sr, -bw / 2f + 0.8f, -h / 2f - 0.2f, z, 2.2f, 0.4f, 0.8f);
        box(sr, bw / 2f - 3f, -h / 2f - 0.2f, z, 2.2f, 0.4f, 0.8f);
    }

    /** a red double-decker: two bands of windows, a black grille strip and destination board */
    private static void bus(ShapeRenderer sr, Shape s, float bw, float h, Color body, boolean inside) {
        float tail = -h / 2f;
        box(sr, -bw / 2f, tail, s.low + 1.2f, bw, h, s.deck - s.low - 1.2f);
        // windows on both decks, all the way round (a hair proud of the body)
        sr.setColor(0.14f, 0.18f, 0.25f, 1f);
        box(sr, -bw / 2f - 0.05f, tail - 0.05f, bw + 0.1f, h + 0.1f, 5f, 3.4f, true);
        box(sr, -bw / 2f - 0.05f, tail - 0.05f, bw + 0.1f, h + 0.1f, 12.5f, 3.6f, true);
        sr.setColor(0.95f, 0.95f, 0.9f, 1f);
        box(sr, -bw / 2f + 1.5f, h / 2f - 0.05f, 9.6f, bw - 3f, 0.2f, 1.3f);
        if (!inside) {
            lights(sr, bw, h, s.low + 1.5f);
        }
    }

    /** a scooter: narrow body, seat, and a rider in a red helmet */
    private static void bike(ShapeRenderer sr, Shape s, float w, float h, Color body, boolean inside) {
        sr.setColor(body);
        box(sr, -1.2f, -h * 0.32f, 1.2f, 2.4f, h * 0.64f, 2.2f);
        sr.setColor(0.12f, 0.1f, 0.1f, 1f);
        box(sr, -1.1f, -h * 0.2f, 3.4f, 2.2f, h * 0.3f, 0.6f);
        if (inside) {
            return;
        }
        // handlebars, the rider's legs, body and helmet
        sr.setColor(0.2f, 0.2f, 0.22f, 1f);
        box(sr, -2.6f, h * 0.25f, 4.6f, 5.2f, 0.5f, 0.5f);
        sr.setColor(0.2f, 0.35f, 0.8f, 1f);
        box(sr, -1.8f, -h * 0.12f, 3.4f, 3.6f, 3.6f, 2.4f);
        sr.setColor(0.95f, 0.8f, 0.2f, 1f);
        box(sr, -1.7f, -h * 0.1f, 5.8f, 3.4f, 2.6f, 3.4f);
        sr.setColor(0.85f, 0.2f, 0.18f, 1f);
        box(sr, -1.2f, -h * 0.08f, 9.2f, 2.4f, 2.4f, 2.2f);
        lights(sr, 3f, h * 0.7f, 2.4f);
    }

    /** an F1 car: slim body, wide wings front and back, airbox, cockpit and halo */
    private static void formula(ShapeRenderer sr, Shape s, float w, float h, Color body, boolean inside) {
        float bw = w * s.width;
        sr.setColor(body);
        box(sr, -bw / 2f, -h / 2f + 3f, 0.6f, bw, h - 5f, 1.8f);
        sr.setColor(body.r * 0.8f, body.g * 0.8f, body.b * 0.8f, 1f);
        box(sr, -bw / 2f - 1.2f, -h * 0.25f, 0.6f, bw + 2.4f, h * 0.35f, 1.6f);   // sidepods
        sr.setColor(0.1f, 0.1f, 0.11f, 1f);
        box(sr, -w / 2f, h / 2f - 2f, 0.3f, w, 1.6f, 0.5f);                       // front wing
        if (inside) {
            return;
        }
        box(sr, -w / 2f + 0.5f, -h / 2f, 3.2f, w - 1f, 1.8f, 0.6f);               // rear wing
        box(sr, -w / 2f + 0.8f, -h / 2f, 0.6f, 0.4f, 1.8f, 3.2f);
        box(sr, w / 2f - 1.2f, -h / 2f, 0.6f, 0.4f, 1.8f, 3.2f);
        sr.setColor(body);
        box(sr, -1f, -h * 0.2f, 2.4f, 2f, 4f, 1.6f);                               // airbox
        sr.setColor(0.08f, 0.08f, 0.09f, 1f);
        box(sr, -1.2f, -h * 0.02f, 2.4f, 2.4f, 2.6f, 0.4f);                        // cockpit
        sr.setColor(0.95f, 0.8f, 0.2f, 1f);
        box(sr, -0.8f, 0.2f, 2.5f, 1.6f, 1.6f, 1.2f);                               // the driver's helmet
        sr.setColor(0.2f, 0.2f, 0.22f, 1f);
        box(sr, -1.3f, h * 0.08f, 3.6f, 2.6f, 0.4f, 0.4f);                         // halo
    }

    /** the moon rover: low frame, two seats, a gold dish on a mast */
    private static void rover(ShapeRenderer sr, Shape s, float bw, float h, float clock) {
        sr.setColor(0.25f, 0.25f, 0.28f, 1f);
        box(sr, -bw / 2f + 1.2f, -h * 0.1f, s.deck, bw / 2f - 1.6f, 3.4f, 2f);
        box(sr, 0.4f, -h * 0.1f, s.deck, bw / 2f - 1.6f, 3.4f, 2f);
        sr.setColor(0.6f, 0.6f, 0.62f, 1f);
        box(sr, -0.25f, -h * 0.35f, s.deck, 0.5f, 0.5f, 5f);
        sr.setColor(0.85f, 0.7f, 0.25f, 1f);
        box(sr, -2f, -h * 0.35f - 0.3f, s.deck + 5f, 4f, 1.2f, 3.4f);
    }

    /** a box standing on z (ShapeRenderer's own box hangs down from z) */
    static void box(ShapeRenderer sr, float x, float y, float z, float w, float l, float d) {
        sr.box(x, y, z + d, w, l, d);
    }

    /** the same, with the arguments in (x, y, width, length, z, height) order */
    private static void box(ShapeRenderer sr, float x, float y, float w, float l, float z, float d, boolean xywl) {
        sr.box(x, y, z + d, w, l, d);
    }
}
