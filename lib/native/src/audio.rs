use crate::{time, FrameDecoder, Frames, InnerPacket, Result};
use crossbeam_utils::atomic::AtomicCell;
use ffmpeg::{codec, decoder, format, frame, software};
use ffmpeg_next as ffmpeg;
use std::iter;
use std::sync::Arc;
use std::time::Duration;

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub struct AudioFrameFormat {
    sample: format::Sample,
    channel_layout: ffmpeg::ChannelLayout,
    sample_rate: u32,
}

impl AudioFrameFormat {
    #[inline]
    pub fn new(sample: format::Sample, channel_layout: ffmpeg::ChannelLayout, sample_rate: u32) -> Self {
        AudioFrameFormat {
            sample,
            channel_layout,
            sample_rate,
        }
    }

    #[inline]
    pub fn sample(&self) -> format::Sample {
        self.sample
    }

    #[inline]
    pub fn channel_layout(&self) -> ffmpeg::ChannelLayout {
        self.channel_layout
    }

    #[inline]
    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }
}

pub struct AudioPacket(pub(super) InnerPacket);

impl AudioPacket {
    pub(super) fn new(packet: ffmpeg::Packet, flush: bool) -> Self {
        AudioPacket(InnerPacket::Packet { packet, flush })
    }

    pub(super) fn eof() -> Self {
        AudioPacket(InnerPacket::Eof)
    }
}

const DST_FRAME_SAMPLE_COUNT: usize = 1024;

pub struct AudioDecoder {
    resources: Arc<DecoderResources>,
    decoder: decoder::Audio,
    resampler: software::resampling::Context,
    src_frame: frame::Audio,
    src_time_base: ffmpeg::Rational,
    dst_sample_duration: ffmpeg::Rational,

    dst_base_timestamp: Option<Duration>,
    current_dst_samples: u64,
}

impl AudioDecoder {
    pub(super) fn new(stream: ffmpeg::Stream, dst_format: AudioFrameFormat) -> Result<Self> {
        let decoder = codec::context::Context::from_parameters(stream.parameters())?
            .decoder()
            .audio()?;
        let src_time_base = decoder.time_base();

        let resampler = Self::create_resampler(&decoder, dst_format);

        Ok(AudioDecoder {
            resources: Arc::new(DecoderResources {
                format: dst_format,
                frame: Default::default(),
            }),
            decoder,
            resampler,
            src_frame: frame::Audio::empty(),
            src_time_base,
            dst_sample_duration: ffmpeg::Rational(1, dst_format.sample_rate as i32),
            dst_base_timestamp: None,
            current_dst_samples: 0,
        })
    }

    fn create_resampler(decoder: &decoder::Audio, dst_format: AudioFrameFormat) -> software::resampling::Context {
        software::resampling::Context::get(
            decoder.format(),
            decoder.channel_layout(),
            decoder.rate(),
            dst_format.sample,
            dst_format.channel_layout,
            dst_format.sample_rate,
        ).expect("Failed to create resampling context")
    }

    fn receive_frame(&mut self) -> Option<Result<AudioFrame>> {
        let mut dst_frame = self.resources.take_frame();

        if self.resampler.delay().is_some() {
            // The resampler still has remaining samples in its internal buffer
            let result = self.resampler.flush(&mut dst_frame);
            return self.handle_resample_result(result, dst_frame);
        }

        match self.decoder.receive_frame(&mut self.src_frame) {
            Ok(_) => {
                if self.dst_base_timestamp.is_none() {
                    self.dst_base_timestamp = Some(time::frame_present_time(&self.src_frame, self.src_time_base));
                }
                let result = self.resampler.run(&self.src_frame, &mut dst_frame);
                self.handle_resample_result(result, dst_frame)
            }
            Err(err) => {
                self.resources.release_frame(dst_frame);
                match err {
                    ffmpeg::Error::Other { errno: ffmpeg::error::EAGAIN } => None,
                    ffmpeg::Error::Eof => None,
                    _ => Some(Err(err.into())),
                }
            }
        }
    }

    fn handle_resample_result(&mut self, result: Result<Option<software::resampling::Delay>, ffmpeg::Error>, dst_frame: frame::Audio) -> Option<Result<AudioFrame>> {
        match result {
            Ok(_) => {
                let present_time = self.dst_base_timestamp.unwrap() + time::to_duration(self.current_dst_samples as i64, self.dst_sample_duration);
                self.current_dst_samples += dst_frame.samples() as u64;
                Some(Ok(AudioFrame {
                    resources: self.resources.clone(),
                    frame: Some(dst_frame),
                    present_time,
                }))
            },
            Err(err) => {
                self.resources.release_frame(dst_frame);
                Some(Err(err.into()))
            },
        }
    }
}

impl FrameDecoder for AudioDecoder {
    type Packet = AudioPacket;
    type Frame = AudioFrame;
    type Frames = AudioFrames;

    #[inline]
    fn send_packet(mut self, packet: AudioPacket) -> Result<AudioFrames> {
        match packet.0.send_to(&mut self.decoder) {
            Ok(flushed) => {
                if flushed {
                    self.resampler = Self::create_resampler(&self.decoder, self.resources.format);
                    self.dst_base_timestamp = None;
                    self.current_dst_samples = 0;
                }
                Ok(AudioFrames(self))
            }
            Err(err) => Err(err.into()),
        }
    }
}

pub struct AudioFrames(AudioDecoder);

impl Frames for AudioFrames {
    type Decoder = AudioDecoder;
    type Frame = AudioFrame;

    #[inline]
    fn decoder(&self) -> &AudioDecoder {
        &self.0
    }

    #[inline]
    fn iter(&mut self) -> impl Iterator<Item = Result<AudioFrame>> {
        iter::from_fn(|| self.0.receive_frame())
    }

    #[inline]
    fn discard(mut self) -> AudioDecoder {
        while self.0.receive_frame().is_some() {}
        self.0
    }
}

pub struct AudioFrame {
    resources: Arc<DecoderResources>,
    frame: Option<frame::Audio>,
    present_time: Duration,
}

impl AudioFrame {
    fn frame(&self) -> &frame::Audio {
        self.frame.as_ref().unwrap()
    }

    #[inline]
    pub fn present_time(&self) -> Duration {
        self.present_time
    }

    #[inline]
    pub fn samples(&self) -> usize {
        self.frame().samples()
    }

    #[inline]
    pub fn bytes(&self) -> usize {
        let frame = self.frame();
        frame.samples() * frame.channels() as usize * frame.format().bytes()
    }

    #[inline]
    pub fn unpack_samples(self, dst: &mut [u8]) -> Result<()> {
        let len_bytes = self.bytes();
        assert!(dst.len() >= len_bytes, "Destination buffer too small, expected at least {} but was {}", len_bytes, dst.len());

        dst[0..len_bytes].copy_from_slice(&self.frame().data(0)[0..len_bytes]);

        Ok(())
    }
}

impl Drop for AudioFrame {
    #[inline]
    fn drop(&mut self) {
        self.resources.release_frame(self.frame.take().unwrap());
    }
}

struct DecoderResources {
    format: AudioFrameFormat,
    frame: AtomicCell<Option<frame::Audio>>,
}

impl DecoderResources {
    fn take_frame(&self) -> frame::Audio {
        self.frame.take().unwrap_or_else(|| frame::Audio::new(
            self.format.sample,
            DST_FRAME_SAMPLE_COUNT,
            self.format.channel_layout
        ))
    }

    fn release_frame(&self, frame: frame::Audio) {
        self.frame.store(Some(frame));
    }
}
