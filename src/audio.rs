//! Small audio helpers shared between the engine and the subtitle pipeline.

/// Centre of the quietest 100 ms window in `samples[from..to]`; used to pick a
/// natural split point when audio must be cut mid-speech.
pub fn find_quietest_split(samples: &[f32], from: usize, to: usize) -> usize {
    const WIN: usize = 1_600; // 100 ms
    if from + WIN > to {
        return to;
    }
    let mut best_pos = to;
    let mut best_energy = f32::MAX;
    let mut i = from;
    while i + WIN <= to {
        let energy: f32 = samples[i..i + WIN].iter().map(|&x| x * x).sum();
        if energy < best_energy {
            best_energy = energy;
            best_pos = i + WIN / 2;
        }
        i += WIN / 2;
    }
    best_pos
}
