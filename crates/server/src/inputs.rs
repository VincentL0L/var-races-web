//! A player's controls waiting to be applied, one per room tick.
//!
//! Every Inputs message carries the newest few inputs, so a lost or late packet is filled in
//! by the next one. The room takes one input per tick; if none has arrived it keeps the last
//! one (the car carries on as it was) for a moment, then lets go of everything: a lost
//! connection or a backgrounded tab can't keep holding the gas. A backlog beyond a few ticks
//! is dropped, so a player whose connection stalled catches up instead of lagging behind.

use std::collections::VecDeque;

use sim::car::Input;

/// inputs kept waiting at most; older ones are dropped
const MAX_BACKLOG: usize = 6;
/// ticks without a new input before the controls are released (about 0.35 s)
const TIMEOUT_TICKS: u32 = 21;

#[derive(Default)]
pub struct InputQueue {
    queue: VecDeque<(u32, Input)>,
    /// the newest input number received
    received: u32,
    /// the input number most recently applied (sent back as the ack)
    pub applied: u32,
    current: Input,
    /// ticks since the last new input
    starved: u32,
}

impl InputQueue {
    /// adds inputs numbered last - n + 1 ..= last, skipping any already received
    pub fn receive(&mut self, last: u32, inputs: &[[u8; 2]]) {
        let n = inputs.len() as u32;
        if n == 0 || last < n - 1 {
            return;
        }
        for (i, packed) in inputs.iter().enumerate() {
            let seq = last - (n - 1) + i as u32;
            if seq > self.received {
                self.received = seq;
                self.queue.push_back((seq, Input::unpack(*packed)));
            }
        }
        while self.queue.len() > MAX_BACKLOG {
            self.queue.pop_front();
        }
    }

    /// the input for this tick
    pub fn next(&mut self) -> Input {
        if let Some((seq, input)) = self.queue.pop_front() {
            self.applied = seq;
            self.current = input;
            self.starved = 0;
        } else {
            self.starved += 1;
            if self.starved > TIMEOUT_TICKS {
                self.current = Input::default();
            }
        }
        self.current
    }

    /// starts over (a new race): nothing held down
    pub fn clear(&mut self) {
        self.queue.clear();
        self.current = Input::default();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn gas(on: bool) -> [u8; 2] {
        Input { gas: on, ..Input::default() }.pack()
    }

    #[test]
    fn redundant_inputs_fill_gaps_once() {
        let mut q = InputQueue::default();
        q.receive(3, &[gas(true), gas(true), gas(false)]);
        // the next message repeats 2 and 3 and adds 4
        q.receive(4, &[gas(true), gas(false), gas(true)]);
        let seqs: Vec<u32> = (0..4).map(|_| {
            q.next();
            q.applied
        }).collect();
        assert_eq!(seqs, vec![1, 2, 3, 4]);
        // nothing new: the last input carries on
        assert!(q.next().gas);
        assert_eq!(q.applied, 4);
    }

    #[test]
    fn silence_lets_go_of_the_controls() {
        let mut q = InputQueue::default();
        q.receive(1, &[gas(true)]);
        for _ in 0..=TIMEOUT_TICKS {
            assert!(q.next().gas);
        }
        assert!(!q.next().gas);
    }

    #[test]
    fn a_long_backlog_is_trimmed() {
        let mut q = InputQueue::default();
        for i in 1..=20 {
            q.receive(i, &[gas(true)]);
        }
        q.next();
        assert_eq!(q.applied, 20 - MAX_BACKLOG as u32 + 1);
    }
}
