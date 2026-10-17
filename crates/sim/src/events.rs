//! What happened during a tick, for sounds, announcements and the HUD. Cars are identified by
//! their slot in the room.

use serde::{Deserialize, Serialize};

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Item {
    Nitro,
    Rocket,
    Oil,
    Bubble,
    Pulse,
    Frost,
    Fire,
}

impl Item {
    pub const ALL: [Item; 7] = [Item::Nitro, Item::Rocket, Item::Oil, Item::Bubble, Item::Pulse, Item::Frost, Item::Fire];

    pub fn label(self) -> &'static str {
        match self {
            Item::Nitro => "Nitro",
            Item::Rocket => "Bottle Rocket",
            Item::Oil => "Oil Slick",
            Item::Bubble => "Bubble Shield",
            Item::Pulse => "Static Pulse",
            Item::Frost => "Frost Blaster",
            Item::Fire => "Fire Blaster",
        }
    }
}

/// what an item does to the car it hits
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Effect {
    Spin,
    Slow,
    Freeze,
    Burn,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum Event {
    /// everyone is ready: the red flag waves before the countdown
    Flag,
    /// 3, 2, 1
    Countdown(u8),
    Go,
    /// picked up an item from a box
    Got { car: u8, item: Item, ammo: u8 },
    /// fired a blaster shot; `left` shots remain
    Shot { car: u8, fire: bool, left: u8 },
    /// used a nitro
    Nitro { car: u8 },
    /// a bubble shield soaked up a hit
    Blocked { car: u8 },
    Hit { car: u8, effect: Effect },
    /// battle damage taken
    Damage { car: u8, amount: u8 },
    /// knocked out of the battle, by whoever hit it last (if recent)
    Out { car: u8, by: Option<u8> },
    BattleOver,
    /// started lap `lap` (2 = the second lap)
    Lap { car: u8, lap: u8 },
    Finish { car: u8, place: u8 },
    /// hit a wall or another car hard
    Crash { car: u8 },
}
