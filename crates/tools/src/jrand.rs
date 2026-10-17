//! java.util.Random, exactly: the generators were written in Java with seeded Randoms, and
//! porting the generator too means the art comes out pixel for pixel the same.

pub struct JRandom {
    seed: i64,
}

const MULTIPLIER: i64 = 0x5DEECE66D;
const ADDEND: i64 = 0xB;
const MASK: i64 = (1 << 48) - 1;

impl JRandom {
    pub fn new(seed: i64) -> JRandom {
        JRandom { seed: (seed ^ MULTIPLIER) & MASK }
    }

    fn next(&mut self, bits: u32) -> i32 {
        self.seed = self.seed.wrapping_mul(MULTIPLIER).wrapping_add(ADDEND) & MASK;
        (self.seed >> (48 - bits)) as i32
    }

    #[cfg(test)]
    pub fn next_i32(&mut self) -> i32 {
        self.next(32)
    }

    /// 0 <= value < bound (bound > 0)
    pub fn next_int(&mut self, bound: i32) -> i32 {
        assert!(bound > 0, "bound must be positive");
        let mut r = self.next(31);
        let m = bound - 1;
        if bound & m == 0 {
            return ((bound as i64 * r as i64) >> 31) as i32;
        }
        let mut u = r;
        loop {
            r = u % bound;
            if u.wrapping_sub(r).wrapping_add(m) >= 0 {
                return r;
            }
            u = self.next(31);
        }
    }

    pub fn next_double(&mut self) -> f64 {
        let hi = (self.next(26) as i64) << 27;
        (hi + self.next(27) as i64) as f64 * (1.0 / (1i64 << 53) as f64)
    }

    pub fn next_float(&mut self) -> f32 {
        self.next(24) as f32 / (1 << 24) as f32
    }

    pub fn next_bool(&mut self) -> bool {
        self.next(1) != 0
    }
}

/// Java's Math.round(double): the nearest whole number, halves rounded up
pub fn round(x: f64) -> i32 {
    (x + 0.5).floor() as i64 as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    /// values from java.util.Random on a JVM
    #[test]
    fn matches_java() {
        let mut r = JRandom::new(42);
        assert_eq!(r.next_int(10), 0);
        assert_eq!(r.next_int(10), 3);
        assert_eq!(r.next_i32(), -1360544799);
        assert_eq!(r.next_int(7), 3);
        assert_eq!(r.next_double(), 0.30871945533265976);
        assert_eq!(r.next_float(), 0.27707845);
        assert!(r.next_bool());
        assert_eq!(r.next_int(1 << 20), 697878);
        assert_eq!(r.next_int(1000000007), 196118093);
        assert_eq!((round(2.5), round(-2.5), round(-0.5)), (3, -2, 0));
    }
}
