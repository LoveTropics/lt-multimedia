use crate::{time, FlushRequest, FrameDecoder, Frames, InnerPacket, Result};
use crossbeam_utils::atomic::AtomicCell;
use ffmpeg::{codec, decoder, format, frame, software};
use ffmpeg_next as ffmpeg;
use std::iter;
use std::sync::Arc;
use std::time::Duration;

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub struct VideoFrameFormat {
    pixel: format::Pixel,
    width: u32,
    height: u32,
    stride: Option<usize>,
}

impl VideoFrameFormat {
    #[inline]
    pub fn new(pixel: format::Pixel, width: u32, height: u32) -> Self {
        VideoFrameFormat {
            pixel,
            width,
            height,
            stride: pixel.descriptor().map(|desc| usize::try_from(width).unwrap() * usize::try_from(desc.nb_components()).unwrap()),
        }
    }

    #[inline]
    pub fn pixel(&self) -> format::Pixel {
        self.pixel
    }

    #[inline]
    pub fn width(&self) -> u32 {
        self.width
    }

    #[inline]
    pub fn height(&self) -> u32 {
        self.height
    }

    #[inline]
    pub fn bytes(&self) -> Option<usize> {
        self.stride.map(|stride| stride * self.height as usize)
    }
}

pub struct VideoPacket(pub(super) InnerPacket);

impl VideoPacket {
    pub(super) fn new(packet: ffmpeg::Packet, flush: Option<FlushRequest>) -> Self {
        VideoPacket(InnerPacket::Packet { packet, flush })
    }

    pub(super) fn eof() -> Self {
        VideoPacket(InnerPacket::Eof)
    }
}

pub struct VideoDecoder {
    resources: Arc<DecoderResources>,
    decoder: decoder::Video,
    format: VideoFrameFormat,
    time_base: ffmpeg::Rational,
    expected_frame_duration: Duration,
    discard_up_to: Duration,
}

impl VideoDecoder {
    pub(super) fn new(stream: ffmpeg::Stream) -> Result<Self> {
        let decoder = codec::context::Context::from_parameters(stream.parameters())?
            .decoder()
            .video()?;

        let format = VideoFrameFormat::new(decoder.format(), decoder.width(), decoder.height());
        let time_base = stream.time_base();
        let expected_frame_duration = time::to_duration(1, stream.rate().invert());

        Ok(VideoDecoder {
            resources: Default::default(),
            decoder,
            format,
            time_base,
            expected_frame_duration,
            discard_up_to: Duration::ZERO,
        })
    }

    #[inline]
    pub fn format(&self) -> VideoFrameFormat {
        self.format
    }

    fn receive_frame(&mut self) -> Option<Result<VideoFrame>> {
        let mut decoded_frame = self.resources.take_frame();
        loop {
           match self.decoder.receive_frame(&mut decoded_frame) {
               Ok(_) => {
                   let present_time = time::frame_present_time(&decoded_frame, self.time_base);
                   if present_time < self.discard_up_to {
                       continue;
                   }
                   let present_duration = time::frame_present_duration(&decoded_frame, self.time_base)
                       .unwrap_or(self.expected_frame_duration);
                   let present_end_time = present_time + present_duration;
                   let format = VideoFrameFormat::new(decoded_frame.format(), decoded_frame.width(), decoded_frame.height());
                   break Some(Ok(VideoFrame {
                       resources: self.resources.clone(),
                       frame: Some(decoded_frame),
                       format,
                       present_time,
                       present_end_time,
                   }))
               }
               Err(err) => {
                   self.resources.release_frame(decoded_frame);
                   break match err {
                       ffmpeg::Error::Other { errno: ffmpeg::error::EAGAIN } => None,
                       ffmpeg::Error::Eof => None,
                       _ => Some(Err(err.into())),
                   }
               }
           }
       }
    }
}

impl FrameDecoder for VideoDecoder {
    type Packet = VideoPacket;
    type Frame = VideoFrame;
    type Frames = VideoFrames;

    #[inline]
    fn send_packet(mut self, packet: VideoPacket) -> Result<VideoFrames> {
        match packet.0.send_to(&mut self.decoder) {
            Ok(_) => Ok(VideoFrames(self)),
            Err(err) => Err(err.into()),
        }
    }
}

pub struct VideoFrames(VideoDecoder);

impl Frames for VideoFrames {
    type Decoder = VideoDecoder;
    type Frame = VideoFrame;

    #[inline]
    fn decoder(&self) -> &VideoDecoder {
        &self.0
    }

    #[inline]
    fn iter(&mut self) -> impl Iterator<Item = Result<VideoFrame>> {
        iter::from_fn(|| self.0.receive_frame())
    }

    #[inline]
    fn discard(mut self) -> VideoDecoder {
        while self.0.receive_frame().is_some() {}
        self.0
    }
}

pub struct VideoFrame {
    resources: Arc<DecoderResources>,
    frame: Option<frame::Video>,
    format: VideoFrameFormat,
    present_time: Duration,
    present_end_time: Duration,
}

impl VideoFrame {
    #[inline]
    pub fn present_time(&self) -> Duration {
        self.present_time
    }

    #[inline]
    pub fn present_end_time(&self) -> Duration {
        self.present_end_time
    }

    #[inline]
    pub fn unpack_pixels(self, dst_format: VideoFrameFormat, dst: &mut [u8]) -> Result<usize> {
        let len_bytes = dst_format.bytes().expect("Unknown destination size");
        assert!(
            dst.len() >= len_bytes,
            "Destination buffer too small, expected at least {} but was {} (for frame format {:?})",
            len_bytes, dst.len(), dst_format
        );

        let src_frame = self.frame.as_ref().unwrap();
        if self.format == dst_format {
            copy_to_buf(src_frame, dst, dst_format);
        } else {
            let mut converter = self.resources.take_converter(self.format, dst_format);
            converter.run(src_frame, dst)?;
            self.resources.release_converter(converter);
        }

        Ok(len_bytes)
    }
}

impl Drop for VideoFrame {
    #[inline]
    fn drop(&mut self) {
        self.resources.release_frame(self.frame.take().unwrap());
    }
}

#[derive(Default)]
struct DecoderResources {
    frame: AtomicCell<Option<frame::Video>>,
    converter: AtomicCell<Option<Converter>>,
}

impl DecoderResources {
    fn take_frame(&self) -> frame::Video {
        self.frame.take().unwrap_or_else(|| frame::Video::empty())
    }

    fn release_frame(&self, frame: frame::Video) {
        self.frame.store(Some(frame));
    }

    fn take_converter(
        &self,
        src_format: VideoFrameFormat,
        dst_format: VideoFrameFormat,
    ) -> Converter {
        match self.converter.take() {
            Some(converter)
                if converter.src_format == src_format && converter.dst_format == dst_format =>
            {
                converter
            }
            _ => Converter::new(src_format, dst_format),
        }
    }

    fn release_converter(&self, converter: Converter) {
        self.converter.store(Some(converter));
    }
}

struct Converter {
    context: software::scaling::Context,
    src_format: VideoFrameFormat,
    dst_format: VideoFrameFormat,
    dst_frame: frame::Video,
}

impl Converter {
    fn new(src_format: VideoFrameFormat, dst_format: VideoFrameFormat) -> Self {
        Converter {
            context: software::scaling::Context::get(
                src_format.pixel,
                src_format.width,
                src_format.height,
                dst_format.pixel,
                dst_format.width,
                dst_format.height,
                software::scaling::Flags::BILINEAR,
            )
            .expect("Failed to create scaling context"),
            src_format,
            dst_format,
            dst_frame: frame::Video::new(dst_format.pixel, dst_format.width, dst_format.height),
        }
    }

    fn run(&mut self, src: &frame::Video, dst: &mut [u8]) -> Result<()> {
        self.context.run(&src, &mut self.dst_frame)?;
        copy_to_buf(&self.dst_frame, dst, self.dst_format);
        Ok(())
    }
}

fn copy_to_buf(src: &frame::Video, dst: &mut [u8], dst_format: VideoFrameFormat) {
    let src_buf = src.data(0);
    let src_stride = src.stride(0);
    let dst_stride = dst_format.stride.expect("Unknown destination stride");
    if src_stride == dst_stride {
        dst[0..src_buf.len()].copy_from_slice(src_buf);
    } else {
        for y in 0..usize::try_from(dst_format.height).unwrap() {
            let dst_start = y * dst_stride;
            let dst_end = dst_start + dst_stride;
            let src_start = y * src_stride;
            let src_end = src_start + dst_stride;
            dst[dst_start..dst_end].copy_from_slice(&src_buf[src_start..src_end]);
        }
    }
}
