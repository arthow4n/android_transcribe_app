//! Keyboard-only streaming dictation coordination.
//!
//! The audio callback owns only a bounded sender and never waits for model
//! inference. A worker thread owns the borrowed transcribe-cpp Stream for its
//! entire lifetime and sends one final result to the Java keyboard callback.

use std::collections::VecDeque;
use std::sync::atomic::AtomicUsize;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crossbeam_channel::{self, Receiver, Sender, TryRecvError};
use jni::objects::GlobalRef;
use jni::JavaVM;

use crate::engine;

/// Maximum queued PCM. At 16 kHz this is 15 minutes of audio (~57 MB).
pub const MAX_QUEUED_SAMPLES: usize = 15 * 60 * 16_000;
const AUDIO_CHUNK_SAMPLES: usize = 1_600; // 100 ms
const SAMPLE_RATE: f64 = 16_000.0;
const SPEED_WINDOW_AUDIO_SECS: f64 = 2.0;

pub struct StreamingControl {
    sender: Sender<Vec<f32>>,
    stop_requested: AtomicBool,
    producer_done: AtomicBool,
    cancel_requested: AtomicBool,
    overflowed: AtomicBool,
    queued_samples: AtomicUsize,
    total_samples: AtomicUsize,
}

impl StreamingControl {
    pub fn push(&self, samples: &[f32]) {
        if self.stop_requested.load(Ordering::Acquire)
            || self.cancel_requested.load(Ordering::Acquire)
        {
            return;
        }
        self.total_samples.fetch_add(samples.len(), Ordering::Relaxed);
        for chunk in samples.chunks(AUDIO_CHUNK_SAMPLES) {
            if !self.reserve(chunk.len()) {
                self.overflowed.store(true, Ordering::Release);
                return;
            }
            if self.sender.send(chunk.to_vec()).is_err() {
                self.queued_samples.fetch_sub(chunk.len(), Ordering::AcqRel);
                return;
            }
        }
    }

    fn reserve(&self, samples: usize) -> bool {
        let mut current = self.queued_samples.load(Ordering::Acquire);
        loop {
            if current.saturating_add(samples) > MAX_QUEUED_SAMPLES {
                return false;
            }
            match self.queued_samples.compare_exchange_weak(
                current,
                current + samples,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => return true,
                Err(updated) => current = updated,
            }
        }
    }

    fn consumed(&self, samples: usize) {
        self.queued_samples.fetch_sub(samples, Ordering::AcqRel);
    }

    /// End capture. The worker drains all chunks accepted before the producer
    /// finished, then finalizes the native stream.
    pub fn finish(&self) {
        self.stop_requested.store(true, Ordering::Release);
        self.producer_done.store(true, Ordering::Release);
    }

    /// Abort without delivering any text.
    pub fn cancel(&self) {
        self.cancel_requested.store(true, Ordering::Release);
        self.stop_requested.store(true, Ordering::Release);
        self.producer_done.store(true, Ordering::Release);
    }

    pub fn overflowed(&self) -> bool {
        self.overflowed.load(Ordering::Acquire)
    }

    pub fn total_samples(&self) -> usize {
        self.total_samples.load(Ordering::Relaxed)
    }
}

fn notify_status(jvm: &JavaVM, target: &GlobalRef, status: &str) {
    if let Ok(mut env) = jvm.attach_current_thread() {
        if let Ok(msg) = env.new_string(status) {
            let _ = env.call_method(
                target.as_obj(),
                "onStatusUpdate",
                "(Ljava/lang/String;)V",
                &[(&msg).into()],
            );
        }
    }
}

fn notify_text(jvm: &JavaVM, target: &GlobalRef, text: &str) {
    if let Ok(mut env) = jvm.attach_current_thread() {
        if let Ok(msg) = env.new_string(text) {
            let _ = env.call_method(
                target.as_obj(),
                "onTextTranscribed",
                "(Ljava/lang/String;)V",
                &[(&msg).into()],
            );
        }
    }
}

fn notify_partial_text(jvm: &JavaVM, target: &GlobalRef, text: &str) {
    if let Ok(mut env) = jvm.attach_current_thread() {
        if let Ok(msg) = env.new_string(text) {
            if let Err(error) = env.call_method(
                target.as_obj(),
                "onStreamingPartialText",
                "(Ljava/lang/String;)V",
                &[(&msg).into()],
            ) {
                let _ = env.exception_clear();
                log::debug!("streaming partial text callback failed: {error}");
            }
        }
    }
}

fn notify_stats(
    jvm: &JavaVM,
    target: &GlobalRef,
    processed_audio_ms: i64,
    total_audio_ms: i64,
    words: usize,
    current_speed: f32,
    average_speed: f32,
) {
    if let Ok(mut env) = jvm.attach_current_thread() {
        if let Err(error) = env.call_method(
            target.as_obj(),
            "onStreamingStats",
            "(JJIFF)V",
            &[
                processed_audio_ms.into(),
                total_audio_ms.into(),
                (words.min(i32::MAX as usize) as i32).into(),
                current_speed.into(),
                average_speed.into(),
            ],
        ) {
            log::warn!("streaming stats callback failed: {error}");
        }
    }
}

fn is_cjk(c: char) -> bool {
    matches!(c,
        '\u{4E00}'..='\u{9FFF}' |   // CJK Unified Ideographs
        '\u{3400}'..='\u{4DBF}' |   // CJK Unified Ideographs Extension A
        '\u{20000}'..='\u{2A6DF}' | // CJK Extension B
        '\u{2A700}'..='\u{2B73F}' | // CJK Extension C
        '\u{2B740}'..='\u{2B81F}' | // CJK Extension D
        '\u{2B820}'..='\u{2CEAF}' | // CJK Extension E
        '\u{F900}'..='\u{FAFF}' |   // CJK Compatibility Ideographs
        '\u{3040}'..='\u{309F}' |   // Hiragana
        '\u{30A0}'..='\u{30FF}' |   // Katakana
        '\u{AC00}'..='\u{D7AF}' |   // Hangul Syllables
        '\u{1100}'..='\u{11FF}'     // Hangul Jamo
    )
}

/// Count ordinary words in the current hypothesis, excluding pure punctuation and
/// counting CJK characters as individual word units. This intentionally remains a display
/// metric: the streaming model may revise a tentative suffix, so it is not a promise
/// that exactly this many words will be inserted when the recording is finalized.
fn count_words(text: &str) -> usize {
    let mut count = 0;
    let mut in_word = false;
    let mut chars = text.chars().peekable();

    while let Some(c) = chars.next() {
        if is_cjk(c) {
            count += 1;
            in_word = false;
        } else if c.is_alphanumeric() {
            if !in_word {
                count += 1;
                in_word = true;
            }
        } else if in_word && (c == '\'' || c == '’' || c == '-' || c == '–' || c == '.') {
            if let Some(&next) = chars.peek() {
                if next.is_alphanumeric() {
                    continue;
                }
            }
            in_word = false;
        } else {
            in_word = false;
        }
    }
    count
}

/// Measures decoder throughput independently of microphone and queue timing.
/// The rolling window is intentionally audio-based: a single feed can be a
/// cheap buffering call or an expensive model step, so a one-call rate is too
/// noisy to be useful in the keyboard.
#[derive(Debug, Default)]
struct ProcessingStats {
    audio_secs: f64,
    processing_secs: f64,
    recent: VecDeque<(f64, f64)>,
}

impl ProcessingStats {
    fn record_feed(&mut self, audio_secs: f64, processing_secs: f64) {
        self.audio_secs += audio_secs;
        self.processing_secs += processing_secs;
        self.recent.push_back((audio_secs, processing_secs));
        self.trim_recent();
    }

    /// Finalization consumes audio already counted by `record_feed`, so only
    /// its compute time is added. Attach it to the newest window entry so the
    /// final current-rate reading includes the flush cost without duplicating
    /// any audio duration.
    fn record_finalize(&mut self, processing_secs: f64) {
        self.processing_secs += processing_secs;
        if let Some((_, recent_processing)) = self.recent.back_mut() {
            *recent_processing += processing_secs;
        }
    }

    fn rates(&self) -> (f32, f32) {
        if self.audio_secs < SPEED_WINDOW_AUDIO_SECS || self.processing_secs <= 0.0 {
            return (-1.0, -1.0);
        }
        let recent_audio: f64 = self.recent.iter().map(|(audio, _)| *audio).sum();
        let recent_processing: f64 = self.recent.iter().map(|(_, processing)| *processing).sum();
        let current = if recent_processing > 0.0 {
            recent_audio / recent_processing
        } else {
            -1.0
        };
        let average = self.audio_secs / self.processing_secs;
        (finite_rate(current), finite_rate(average))
    }

    fn audio_ms(&self) -> i64 {
        (self.audio_secs * 1000.0)
            .round()
            .clamp(0.0, i64::MAX as f64) as i64
    }

    fn trim_recent(&mut self) {
        let mut recent_audio: f64 = self.recent.iter().map(|(audio, _)| *audio).sum();
        while recent_audio - self.recent.front().map(|(audio, _)| *audio).unwrap_or(0.0)
            >= SPEED_WINDOW_AUDIO_SECS
        {
            if let Some((audio, _)) = self.recent.pop_front() {
                recent_audio -= audio;
            } else {
                break;
            }
        }
    }
}

fn finite_rate(rate: f64) -> f32 {
    if rate.is_finite() && rate > 0.0 {
        rate.min(f32::MAX as f64) as f32
    } else {
        -1.0
    }
}

/// Start a worker for an already loaded streaming-capable engine.
pub fn start(
    engine: Arc<std::sync::Mutex<engine::Engine>>,
    jvm: Arc<JavaVM>,
    target: GlobalRef,
) -> Arc<StreamingControl> {
    let (sender, receiver) = crossbeam_channel::unbounded();
    let control = Arc::new(StreamingControl {
        sender,
        stop_requested: AtomicBool::new(false),
        producer_done: AtomicBool::new(false),
        cancel_requested: AtomicBool::new(false),
        overflowed: AtomicBool::new(false),
        queued_samples: AtomicUsize::new(0),
        total_samples: AtomicUsize::new(0),
    });
    let worker_control = control.clone();
    std::thread::spawn(move || {
        let result = run_worker(
            &mut engine.lock().unwrap_or_else(|e| e.into_inner()),
            &receiver,
            &worker_control,
            &jvm,
            &target,
        );
        match result {
            Ok(Some(text)) => {
                notify_status(&jvm, &target, "Ready");
                notify_text(&jvm, &target, &text);
            }
            Ok(None) => notify_status(&jvm, &target, "Canceled"),
            Err(error) => notify_status(&jvm, &target, &format!("Error: {}", error)),
        }
    });
    control
}

fn run_worker(
    engine: &mut engine::Engine,
    receiver: &Receiver<Vec<f32>>,
    control: &StreamingControl,
    jvm: &JavaVM,
    target: &GlobalRef,
) -> Result<Option<String>, String> {
    let mut stream = engine.begin_streaming()?;
    let mut last_stats = Instant::now();
    let mut last_stats_log = Instant::now();
    let mut stats = ProcessingStats::default();
    let mut finalizing = false;
    loop {
        if control.cancel_requested.load(Ordering::Acquire) {
            stream.reset();
            return Ok(None);
        }

        if control.stop_requested.load(Ordering::Acquire) {
            finalizing = true;
        }

        let next = if finalizing {
            receiver.try_recv().map_err(|error| match error {
                TryRecvError::Empty => TryRecvError::Empty,
                TryRecvError::Disconnected => TryRecvError::Disconnected,
            })
        } else {
            match receiver.recv_timeout(Duration::from_millis(20)) {
                Ok(samples) => Ok(samples),
                Err(crossbeam_channel::RecvTimeoutError::Timeout) => continue,
                Err(crossbeam_channel::RecvTimeoutError::Disconnected) => {
                    Err(TryRecvError::Disconnected)
                }
            }
        };

        match next {
            Ok(mut samples) => {
                control.consumed(samples.len());
                // Coalesce additional ready chunks from the channel up to ~200ms
                // (3200 samples) to dramatically reduce per-chunk mel spectrogram
                // recomputation overhead in transcribe-cpp when processing a backlog.
                while samples.len() < AUDIO_CHUNK_SAMPLES * 2 {
                    match receiver.try_recv() {
                        Ok(more) => {
                            control.consumed(more.len());
                            samples.extend_from_slice(&more);
                        }
                        Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
                    }
                }

                if samples.is_empty() {
                    continue;
                }
                let feed_started = Instant::now();
                stream.feed(&samples).map_err(|e| e.to_string())?;
                stats.record_feed(
                    samples.len() as f64 / SAMPLE_RATE,
                    feed_started.elapsed().as_secs_f64(),
                );
                // Keep JNI traffic low enough for the audio thread and main
                // looper while still making the keyboard feel live.
                let stats_cadence = if finalizing {
                    Duration::from_millis(100)
                } else {
                    Duration::from_millis(250)
                };
                if last_stats.elapsed() >= stats_cadence {
                    let snapshot = stream.text();
                    let (current_speed, average_speed) = stats.rates();
                    let total_ms =
                        (control.total_samples() as f64 / (SAMPLE_RATE / 1000.0)).round() as i64;
                    notify_stats(
                        &jvm,
                        &target,
                        stats.audio_ms(),
                        total_ms,
                        count_words(&snapshot.full),
                        current_speed,
                        average_speed,
                    );
                    if !snapshot.full.trim().is_empty() {
                        notify_partial_text(&jvm, &target, &snapshot.full);
                    }
                    if last_stats_log.elapsed() >= Duration::from_secs(1) {
                        log::debug!(
                            "stream metrics: audio={}ms total={}ms current={:.3}x average={:.3}x",
                            stats.audio_ms(),
                            total_ms,
                            current_speed,
                            average_speed
                        );
                        last_stats_log = Instant::now();
                    }
                    last_stats = Instant::now();
                }
            }
            Err(TryRecvError::Empty) if finalizing => {
                if control.producer_done.load(Ordering::Acquire) {
                    if control.overflowed() {
                        log::warn!(
                            "streaming audio buffer limit reached ({} samples); finalizing available audio",
                            MAX_QUEUED_SAMPLES
                        );
                    }
                    let finalize_started = Instant::now();
                    stream.finalize().map_err(|e| e.to_string())?;
                    stats.record_finalize(finalize_started.elapsed().as_secs_f64());
                    let snapshot = stream.text();
                    let (current_speed, average_speed) = stats.rates();
                    let total_ms =
                        (control.total_samples() as f64 / (SAMPLE_RATE / 1000.0)).round() as i64;
                    notify_stats(
                        &jvm,
                        &target,
                        total_ms.max(stats.audio_ms()),
                        total_ms,
                        count_words(&snapshot.full),
                        current_speed,
                        average_speed,
                    );
                    log::debug!(
                        "stream metrics final: audio={}ms total={}ms current={:.3}x average={:.3}x",
                        stats.audio_ms(),
                        total_ms,
                        current_speed,
                        average_speed
                    );
                    let text = snapshot.full;
                    drop(stream);
                    return Ok(Some(engine.convert_text(&text)));
                }
                finalizing = false;
            }
            Err(TryRecvError::Empty) => continue,
            Err(TryRecvError::Disconnected) => {
                stream.reset();
                return Err("streaming audio worker disconnected".into());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn control() -> (Arc<StreamingControl>, Receiver<Vec<f32>>) {
        let (sender, receiver) = crossbeam_channel::unbounded();
        (
            Arc::new(StreamingControl {
                sender,
                stop_requested: AtomicBool::new(false),
                producer_done: AtomicBool::new(false),
                cancel_requested: AtomicBool::new(false),
                overflowed: AtomicBool::new(false),
                queued_samples: AtomicUsize::new(0),
                total_samples: AtomicUsize::new(0),
            }),
            receiver,
        )
    }

    #[test]
    fn push_splits_audio_and_preserves_sample_count() {
        let (control, receiver) = control();
        control.push(&vec![0.25; AUDIO_CHUNK_SAMPLES * 2 + 1]);
        let first = receiver.recv().unwrap();
        let second = receiver.recv().unwrap();
        let third = receiver.recv().unwrap();
        assert_eq!(first.len(), AUDIO_CHUNK_SAMPLES);
        assert_eq!(second.len(), AUDIO_CHUNK_SAMPLES);
        assert_eq!(third.len(), 1);
        assert_eq!(
            first.len() + second.len() + third.len(),
            AUDIO_CHUNK_SAMPLES * 2 + 1
        );
        assert_eq!(control.total_samples(), AUDIO_CHUNK_SAMPLES * 2 + 1);
        assert!(!control.overflowed());
    }

    #[test]
    fn queue_overflow_is_reported_without_blocking() {
        let (control, _receiver) = control();
        control
            .queued_samples
            .store(MAX_QUEUED_SAMPLES, Ordering::Release);
        control.push(&[0.0]);
        assert!(control.overflowed());
        assert!(control.queued_samples.load(Ordering::Acquire) <= MAX_QUEUED_SAMPLES);
    }

    #[test]
    fn finish_and_cancel_close_the_producer() {
        let (control, _receiver) = control();
        control.finish();
        assert!(control.stop_requested.load(Ordering::Acquire));
        assert!(control.producer_done.load(Ordering::Acquire));
        control.cancel();
        assert!(control.cancel_requested.load(Ordering::Acquire));
    }

    #[test]
    fn processing_rates_ignore_capture_idle_time() {
        let mut stats = ProcessingStats::default();
        stats.record_feed(1.0, 0.5);
        stats.record_feed(1.0, 0.5);
        let (current, average) = stats.rates();
        assert!((current - 2.0).abs() < f32::EPSILON);
        assert!((average - 2.0).abs() < f32::EPSILON);
    }

    #[test]
    fn processing_rates_are_unavailable_during_warmup() {
        let mut stats = ProcessingStats::default();
        stats.record_feed(1.9, 0.5);
        assert_eq!(stats.rates(), (-1.0, -1.0));
    }

    #[test]
    fn finalization_adds_compute_time_without_audio_duplication() {
        let mut stats = ProcessingStats::default();
        stats.record_feed(2.0, 1.0);
        stats.record_finalize(1.0);
        assert_eq!(stats.audio_ms(), 2_000);
        let (current, average) = stats.rates();
        assert!((current - 1.0).abs() < f32::EPSILON);
        assert!((average - 1.0).abs() < f32::EPSILON);
    }

    #[test]
    fn batch_coalesces_ready_chunks_up_to_limit() {
        let (control, receiver) = control();
        for _ in 0..10 {
            control.push(&vec![0.1; 200]);
        }
        assert_eq!(control.queued_samples.load(Ordering::Acquire), 2000);
        let mut batch = receiver.recv().unwrap();
        control.consumed(batch.len());
        while batch.len() < AUDIO_CHUNK_SAMPLES * 2 {
            match receiver.try_recv() {
                Ok(more) => {
                    control.consumed(more.len());
                    batch.extend_from_slice(&more);
                }
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
            }
        }
        assert_eq!(batch.len(), 2000);
        assert_eq!(control.queued_samples.load(Ordering::Acquire), 0);
    }
}
