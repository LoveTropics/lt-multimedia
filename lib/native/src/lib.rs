mod audio;
mod java;
mod video;

use ffmpeg::{format, media};
use ffmpeg_next as ffmpeg;

pub use audio::*;
use crossbeam_utils::atomic::AtomicCell;
use std::ffi::{c_int, c_uchar, c_void, CString, NulError};
use std::pin::Pin;
use std::sync::Once;
use std::{io, ptr, slice};
pub use video::*;

type Result<T, E = Error> = std::result::Result<T, E>;

static INIT: Once = Once::new();

fn ensure_initialized() {
    INIT.call_once(|| ffmpeg::init().unwrap());
}

#[derive(thiserror::Error, Debug)]
pub enum Error {
    #[error("io error: {0}")]
    Io(#[from] io::Error),
    #[error("ffmpeg error: {0}")]
    Ffmpeg(#[from] ffmpeg::Error),
    #[error("null character in string")]
    Nul(#[from] NulError),
}

pub enum MultimediaPacket {
    Video(VideoPacket),
    Audio(AudioPacket),
}

struct ReadState<R: io::Read> {
    read: R,
    last_error: AtomicCell<Option<io::Error>>,
}

impl<R: io::Read> ReadState<R> {
    fn map_error(&self, err: ffmpeg::Error) -> Error {
        match err {
            ffmpeg::Error::External => self
                .last_error
                .take()
                .map(Error::Io)
                .unwrap_or(Error::Ffmpeg(ffmpeg::Error::External)),
            err => Error::Ffmpeg(err),
        }
    }
}

pub struct MultimediaReader<R: io::Read> {
    input: format::context::Input,
    // Must be pinned, as it is implicitly referenced by the ffmpeg input context
    read_state: Pin<Box<ReadState<R>>>,

    video_stream_index: Option<usize>,
    audio_stream_index: Option<usize>,
    video_eof: bool,
    audio_eof: bool,
}

impl<R: io::Read> MultimediaReader<R> {
    pub fn open(file_name: impl AsRef<str>, read: R) -> Result<Self> {
        ensure_initialized();

        let mut read_state = Box::pin(ReadState {
            read,
            last_error: AtomicCell::new(None),
        });

        let input =
            unsafe { open_input_context(CString::new(file_name.as_ref())?, &mut read_state) }
                .map_err(|err| read_state.map_error(err))?;
        let video_stream_index = input.streams().best(media::Type::Video).map(|s| s.index());
        let audio_stream_index = input.streams().best(media::Type::Audio).map(|s| s.index());

        Ok(Self {
            input,
            read_state,
            video_stream_index,
            audio_stream_index,
            video_eof: false,
            audio_eof: false,
        })
    }

    pub fn read_packet(&mut self) -> Option<Result<MultimediaPacket>> {
        loop {
            match self.input.packets().next() {
                Some((stream, packet)) => {
                    if Some(stream.index()) == self.video_stream_index {
                        break Some(Ok(MultimediaPacket::Video(VideoPacket::new(packet))));
                    } else if Some(stream.index()) == self.audio_stream_index {
                        break Some(Ok(MultimediaPacket::Audio(AudioPacket::new(packet))));
                    }
                }
                None => {
                    break if let Some(err) = self.read_state.last_error.take() {
                        Some(Err(err.into()))
                    } else if !self.video_eof {
                        self.video_eof = true;
                        Some(Ok(MultimediaPacket::Video(VideoPacket::eof())))
                    } else if !self.audio_eof {
                        self.audio_eof = true;
                        Some(Ok(MultimediaPacket::Audio(AudioPacket::eof())))
                    } else {
                        None
                    }
                },
            }
        }
    }

    pub fn open_video_decoder(&self) -> Result<Option<VideoDecoder>> {
        match self.video_stream_index {
            Some(index) => {
                let stream = self.input.stream(index).unwrap();
                Ok(Some(VideoDecoder::new(stream)?))
            }
            None => Ok(None),
        }
    }

    pub fn open_audio_decoder(&self, format: AudioFrameFormat) -> Result<Option<AudioDecoder>> {
        match self.audio_stream_index {
            Some(index) => {
                let stream = self.input.stream(index).unwrap();
                Ok(Some(AudioDecoder::new(stream, format)?))
            }
            None => Ok(None),
        }
    }
}

unsafe fn open_input_context<R: io::Read>(
    file_name: CString,
    read: &mut Pin<Box<ReadState<R>>>,
) -> Result<format::context::Input, ffmpeg::Error> {
    const READ_BUFFER_SIZE: usize = 4096;

    unsafe {
        let buffer = ffmpeg::ffi::av_malloc(READ_BUFFER_SIZE) as *mut c_uchar;
        assert!(!buffer.is_null(), "Failed to allocate input buffer");

        let mut context = ffmpeg::ffi::avformat_alloc_context();
        (*context).pb = ffmpeg::ffi::avio_alloc_context(
            buffer,
            READ_BUFFER_SIZE as c_int,
            0,
            read.as_mut().get_unchecked_mut() as *mut ReadState<R> as *mut c_void,
            Some(read_packet::<R>),
            None,
            None,
        );

        match ffmpeg::ffi::avformat_open_input(
            &mut context,
            file_name.as_ptr(),
            ptr::null_mut(),
            ptr::null_mut(),
        ) {
            0 => match ffmpeg::ffi::avformat_find_stream_info(context, ptr::null_mut()) {
                r if r >= 0 => Ok(format::context::Input::wrap(context)),
                err => {
                    ffmpeg::ffi::avformat_close_input(&mut context);
                    Err(ffmpeg::Error::from(err))
                }
            },
            err => Err(ffmpeg::Error::from(err)),
        }
    }
}

unsafe extern "C" fn read_packet<R: io::Read>(
    opaque: *mut c_void,
    buf: *mut u8,
    buf_size: c_int,
) -> c_int {
    let read_state = unsafe { &mut *(opaque as *mut ReadState<R>) };
    let buf = unsafe { slice::from_raw_parts_mut(buf, buf_size as usize) };
    match read_state.read.read(buf) {
        Ok(0) => ffmpeg::Error::Eof.into(),
        Ok(bytes) => bytes as c_int,
        Err(err) => {
            read_state.last_error.store(Some(err));
            ffmpeg::Error::External.into()
        }
    }
}

pub trait FrameDecoder {
    type Packet;
    type Frame: 'static;
    type Frames: Frames<Decoder = Self, Frame = Self::Frame>;

    fn send_packet(self, packet: Self::Packet) -> Result<Self::Frames>;
}

pub trait Frames {
    type Decoder;
    type Frame: 'static;

    fn decoder(&self) -> &Self::Decoder;

    fn iter(&mut self) -> impl Iterator<Item = Result<Self::Frame>>;

    fn discard(self) -> Self::Decoder;
}

pub(crate) enum InnerPacket {
    Packet(ffmpeg::Packet),
    Eof,
}

impl InnerPacket {
    pub fn send_to(self, decoder: &mut ffmpeg::decoder::Opened) -> Result<(), ffmpeg::Error> {
        match self {
            InnerPacket::Packet(packet) => decoder.send_packet(&packet),
            InnerPacket::Eof => decoder.send_eof(),
        }
    }
}
