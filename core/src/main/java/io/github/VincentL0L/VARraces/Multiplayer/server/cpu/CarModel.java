package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

/**
 * The cars in the garage. Each one trades something off: a quick launch or a high top
 * speed, sharp handling or a heavy body that shoves other cars around.
 *
 * A car is picked as a number (1, 2, 3...): model = (number - 1) / 3 and paint = (number - 1) % 3,
 * the same number the server passes to every player.
 */
public class CarModel {
    public static final int PAINTS = 3;

    public final String id;
    public final String name;
    public final String tagline;
    /** how hard the tires can launch the car (x the standard car) */
    public final float traction;
    /** engine power (x standard): sets the top speed */
    public final float power;
    /** how fast it turns and how well the tires hold (x standard) */
    public final float handling;
    /** weight (x standard): heavier cars shove lighter ones, and get shoved less */
    public final float mass;

    private CarModel(String id, String name, String tagline, float traction, float power, float handling, float mass) {
        this.id = id;
        this.name = name;
        this.tagline = tagline;
        this.traction = traction;
        this.power = power;
        this.handling = handling;
        this.mass = mass;
    }

    public static final CarModel[] ALL = {
        new CarModel("sprinter", "Sprinter", "Light and nimble. Off the line first, out of puff on the straights.",
            1.35f, 0.86f, 1.18f, 0.75f),
        new CarModel("roadster", "Roadster", "Balanced in every way. A good car to learn on.",
            1f, 1f, 1f, 1f),
        new CarModel("gt", "GT", "The fastest flat out, but it takes a while to get there.",
            0.82f, 1.3f, 0.95f, 0.95f),
        new CarModel("muscle", "Muscle", "Big power and a hard launch. Slides around in the corners.",
            1.15f, 1.18f, 0.78f, 1.25f),
        new CarModel("hauler", "Hauler", "Slow as a fridge, but nobody pushes it around. Bump everyone.",
            0.72f, 0.82f, 0.9f, 2f),
    };

    /**
     * @param car a car number (1 = Sprinter paint 1 ... 15 = Hauler paint 3)
     * @return its model (anything unknown gives the Roadster)
     */
    public static CarModel of(int car) {
        int m = (car - 1) / PAINTS;
        return m >= 0 && m < ALL.length ? ALL[m] : ALL[1];
    }

    /**
     * @param car a car number
     * @return its sprite in assets
     */
    public static String sprite(int car) {
        int m = Math.max(0, Math.min(ALL.length - 1, (car - 1) / PAINTS));
        int p = Math.max(0, (car - 1) % PAINTS);
        return "ui/cars/" + ALL[m].id + "_" + p + ".png";
    }

    /**
     * @param model 0-4   @param paint 0-2
     * @return the car number
     */
    public static int number(int model, int paint) {
        return model * PAINTS + paint + 1;
    }

    /** how many car numbers there are */
    public static int count() {
        return ALL.length * PAINTS;
    }

    // ratings out of 10 for the garage's stat bars (worked out from the physics)

    /** top speed: power and air drag balance at v = cbrt(power / drag) */
    public int speedRating() {
        return rate((float) Math.cbrt(power), 0.94f, 1.1f);
    }

    public int accelRating() {
        return rate(traction * 0.7f + (float) Math.cbrt(power) * 0.3f, 0.78f, 1.3f);
    }

    public int handlingRating() {
        return rate(handling, 0.75f, 1.2f);
    }

    public int weightRating() {
        return rate(mass, 0.7f, 2f);
    }

    private static int rate(float value, float low, float high) {
        return Math.max(1, Math.min(10, Math.round(1 + 9 * (value - low) / (high - low))));
    }
}
