//! Small 2D math helpers. Every transcendental function goes through `libm`, so the
//! server (native) and the browser (WebAssembly) compute bit-identical results.

use core::f32::consts::PI;
use core::ops::{Add, AddAssign, Mul, Neg, Sub, SubAssign};

use serde::{Deserialize, Serialize};

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Vec2 {
    pub x: f32,
    pub y: f32,
}

impl Vec2 {
    pub const ZERO: Vec2 = Vec2 { x: 0.0, y: 0.0 };

    pub const fn new(x: f32, y: f32) -> Vec2 {
        Vec2 { x, y }
    }

    /// unit vector pointing along `angle` (radians, 0 = +x, counter-clockwise)
    pub fn from_angle(angle: f32) -> Vec2 {
        Vec2::new(cos(angle), sin(angle))
    }

    pub fn dot(self, o: Vec2) -> f32 {
        self.x * o.x + self.y * o.y
    }

    /// z of the 3D cross product: positive when `o` is to the left of `self`
    pub fn cross(self, o: Vec2) -> f32 {
        self.x * o.y - self.y * o.x
    }

    pub fn len2(self) -> f32 {
        self.dot(self)
    }

    pub fn len(self) -> f32 {
        sqrt(self.len2())
    }

    pub fn dist(self, o: Vec2) -> f32 {
        (self - o).len()
    }

    pub fn dist2(self, o: Vec2) -> f32 {
        (self - o).len2()
    }

    /// this vector scaled to length 1 (zero stays zero)
    pub fn norm(self) -> Vec2 {
        let l = self.len();
        if l > 1e-9 {
            self * (1.0 / l)
        } else {
            Vec2::ZERO
        }
    }

    /// rotated 90 degrees counter-clockwise (to the left)
    pub fn perp(self) -> Vec2 {
        Vec2::new(-self.y, self.x)
    }

    pub fn angle(self) -> f32 {
        atan2(self.y, self.x)
    }

    pub fn lerp(self, o: Vec2, t: f32) -> Vec2 {
        self + (o - self) * t
    }
}

impl Add for Vec2 {
    type Output = Vec2;
    fn add(self, o: Vec2) -> Vec2 {
        Vec2::new(self.x + o.x, self.y + o.y)
    }
}

impl AddAssign for Vec2 {
    fn add_assign(&mut self, o: Vec2) {
        self.x += o.x;
        self.y += o.y;
    }
}

impl Sub for Vec2 {
    type Output = Vec2;
    fn sub(self, o: Vec2) -> Vec2 {
        Vec2::new(self.x - o.x, self.y - o.y)
    }
}

impl SubAssign for Vec2 {
    fn sub_assign(&mut self, o: Vec2) {
        self.x -= o.x;
        self.y -= o.y;
    }
}

impl Mul<f32> for Vec2 {
    type Output = Vec2;
    fn mul(self, s: f32) -> Vec2 {
        Vec2::new(self.x * s, self.y * s)
    }
}

impl Neg for Vec2 {
    type Output = Vec2;
    fn neg(self) -> Vec2 {
        Vec2::new(-self.x, -self.y)
    }
}

pub fn sin(a: f32) -> f32 {
    libm::sinf(a)
}

pub fn cos(a: f32) -> f32 {
    libm::cosf(a)
}

pub fn atan2(y: f32, x: f32) -> f32 {
    libm::atan2f(y, x)
}

pub fn exp(a: f32) -> f32 {
    libm::expf(a)
}

pub fn sqrt(a: f32) -> f32 {
    libm::sqrtf(a)
}

pub fn pow(a: f32, b: f32) -> f32 {
    libm::powf(a, b)
}

pub fn cbrt(a: f32) -> f32 {
    libm::cbrtf(a)
}

pub fn ceil(a: f32) -> f32 {
    libm::ceilf(a)
}

pub fn round(a: f32) -> f32 {
    libm::roundf(a)
}

pub fn clamp(v: f32, lo: f32, hi: f32) -> f32 {
    if v < lo {
        lo
    } else if v > hi {
        hi
    } else {
        v
    }
}

pub fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

pub fn signum(v: f32) -> f32 {
    if v > 0.0 {
        1.0
    } else if v < 0.0 {
        -1.0
    } else {
        0.0
    }
}

pub fn deg(degrees: f32) -> f32 {
    degrees * (PI / 180.0)
}

pub fn to_deg(radians: f32) -> f32 {
    radians * (180.0 / PI)
}

/// `a - b` wrapped to -PI..PI: the shortest turn from heading b to heading a
pub fn angle_diff(a: f32, b: f32) -> f32 {
    let mut d = libm::fmodf(a - b, 2.0 * PI);
    if d > PI {
        d -= 2.0 * PI;
    }
    if d < -PI {
        d += 2.0 * PI;
    }
    d
}

/// an angle wrapped to 0..2PI
pub fn wrap_angle(a: f32) -> f32 {
    let w = libm::fmodf(a, 2.0 * PI);
    if w < 0.0 {
        w + 2.0 * PI
    } else {
        w
    }
}

/// moves `value` toward 0 by `amount` without passing it
pub fn approach_zero(value: f32, amount: f32) -> f32 {
    if value > 0.0 {
        (value - amount).max(0.0)
    } else {
        (value + amount).min(0.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn angle_diff_takes_the_short_way() {
        assert!((angle_diff(deg(350.0), deg(10.0)) - deg(-20.0)).abs() < 1e-5);
        assert!((angle_diff(deg(10.0), deg(350.0)) - deg(20.0)).abs() < 1e-5);
        assert!((angle_diff(deg(90.0), deg(-90.0)).abs() - PI).abs() < 1e-5);
    }
}
