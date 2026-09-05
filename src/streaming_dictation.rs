//! Keyboard-only streaming dictation coordination.
//!
//! The audio callback owns only a bounded sender and never waits for model
//! inference. A worker thread owns the borrowed transcribe-cpp Stream for its
//! entire lifetime and sends one final result to the Java keyboard callback.

use std::sync::atomic::AtomicUsize;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, SyncSender, TryRecvError, TrySendError};
use std::sync::Arc;
use std::time::Duration;

use jni::objects::GlobalRef;
use jni::JavaVM;

use crate::engine;

/// Maximum queued PCM. At 16 kHz this is 30 seconds of audio.
pub const MAX_QUEUED_SAMPLES: usize = 30 * 16_000;
const AUDIO_CHUNK_SAMPLES: usize = 1_600; // 100 ms

pub struct StreamingControl {
    sender: SyncSender<Vec<f32>>,
    stop_requested: AtomicBool,
    producer_done: AtomicBool,
    cancel_requested: AtomicBool,
    overflowed: AtomicBool,
    queued_samples: AtomicUsize,
}

impl StreamingControl {
    pub fn push(&self, samples: &[f32]) {
        if self.stop_requested.load(Ordering::Acquire)
            || self.cancel_requested.load(Ordering::Acquire)
        {
            return;
        }
        for chunk in samples.chunks(AUDIO_CHUNK_SAMPLES) {
            if !self.reserve(chunk.len()) {
                self.overflowed.store(true, Ordering::Release);
                return;
            }
            match self.sender.try_send(chunk.to_vec()) {
                Ok(()) => {}
                Err(TrySendError::Full(_)) | Err(TrySendError::Disconnected(_)) => {
                    self.queued_samples.fetch_sub(chunk.len(), Ordering::AcqRel);
                    self.overflowed.store(true, Ordering::Release);
                    return;
                }
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

/// Start a worker for an already loaded streaming-capable engine.
pub fn start(
    engine: Arc<std::sync::Mutex<engine::Engine>>,
    jvm: Arc<JavaVM>,
    target: GlobalRef,
) -> Arc<StreamingControl> {
    let (sender, receiver) = mpsc::sync_channel(MAX_QUEUED_SAMPLES / AUDIO_CHUNK_SAMPLES);
    let control = Arc::new(StreamingControl {
        sender,
        stop_requested: AtomicBool::new(false),
        producer_done: AtomicBool::new(false),
        cancel_requested: AtomicBool::new(false),
        overflowed: AtomicBool::new(false),
        queued_samples: AtomicUsize::new(0),
    });
    let worker_control = control.clone();
    std::thread::spawn(move || {
        let result = run_worker(
            &mut engine.lock().unwrap_or_else(|e| e.into_inner()),
            &receiver,
            &worker_control,
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
    receiver: &mpsc::Receiver<Vec<f32>>,
    control: &StreamingControl,
) -> Result<Option<String>, String> {
    let mut stream = engine.begin_streaming()?;
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
                Err(mpsc::RecvTimeoutError::Timeout) => continue,
                Err(mpsc::RecvTimeoutError::Disconnected) => Err(TryRecvError::Disconnected),
            }
        };

        match next {
            Ok(samples) => {
                control.consumed(samples.len());
                if samples.is_empty() {
                    continue;
                }
                stream.feed(&samples).map_err(|e| e.to_string())?;
            }
            Err(TryRecvError::Empty) if finalizing => {
                if control.producer_done.load(Ordering::Acquire) {
                    if control.overflowed() {
                        stream.reset();
                        return Err("streaming audio buffer filled; try a shorter recording".into());
                    }
                    stream.finalize().map_err(|e| e.to_string())?;
                    let text = stream.text().full;
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

    fn control() -> (Arc<StreamingControl>, mpsc::Receiver<Vec<f32>>) {
        let (sender, receiver) = mpsc::sync_channel(MAX_QUEUED_SAMPLES / AUDIO_CHUNK_SAMPLES);
        (
            Arc::new(StreamingControl {
                sender,
                stop_requested: AtomicBool::new(false),
                producer_done: AtomicBool::new(false),
                cancel_requested: AtomicBool::new(false),
                overflowed: AtomicBool::new(false),
                queued_samples: AtomicUsize::new(0),
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
        assert!(!control.overflowed());
    }

    #[test]
    fn queue_overflow_is_reported_without_blocking() {
        let (control, _receiver) = control();
        control.push(&vec![0.0; MAX_QUEUED_SAMPLES + 1]);
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
}
