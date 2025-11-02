mod audio;
mod java;
pub(crate) mod time;
mod video;

use ffmpeg::{format, media};
use ffmpeg_next as ffmpeg;

pub use audio::*;
use crossbeam_utils::atomic::AtomicCell;
use std::ffi::{c_int, c_uchar, c_void, CString, NulError};
use std::path::Path;
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

struct ReadState<R> {
    read: R,
    last_error: AtomicCell<Option<io::Error>>,
}

impl<R> ReadState<R> {
    fn store_error(&self, err: io::Error) {
        self.last_error.store(Some(err))
    }

    fn take_error(&self) -> Option<io::Error> {
        self.last_error.take()
    }

    fn map_error(&self, err: ffmpeg::Error) -> Error {
        match err {
            ffmpeg::Error::External => self.take_error()
                .map(Error::Io)
                .unwrap_or(Error::Ffmpeg(ffmpeg::Error::External)),
            err => Error::Ffmpeg(err),
        }
    }
}

pub struct MultimediaReader<R> {
    input: format::context::Input,
    // Must be pinned, as it is implicitly referenced by the ffmpeg input context
    read_state: Pin<Box<ReadState<R>>>,

    video_stream_index: Option<usize>,
    audio_stream_index: Option<usize>,
    video_eof: bool,
    audio_eof: bool,
}

impl<R: io::Read> MultimediaReader<R> {
    pub fn open_stream(read: R) -> Result<Self> {
        Self::open(read, |mut read_state| unsafe {
            open_custom_io_input_context(
                &mut read_state,
                io_read::<R>,
                None,
            )
        })
    }
}

impl<R: io::Read + io::Seek> MultimediaReader<R> {
    pub fn open_seekable(read: R) -> Result<Self> {
        Self::open(read, |mut read_state| unsafe {
            open_custom_io_input_context(
                &mut read_state,
                io_read::<R>,
                Some(io_seek::<R>),
            )
        })
    }
}

impl MultimediaReader<()> {
    pub fn open_path(path: impl AsRef<Path>) -> Result<Self> {
        Self::open((), |_| format::input(path.as_ref()))
    }
}

impl<R> MultimediaReader<R> {
    fn open<F>(read: R, context_factory: F) -> Result<Self>
    where
        F: FnOnce(&mut Pin<Box<ReadState<R>>>) -> Result<format::context::Input, ffmpeg::Error>,
    {
        ensure_initialized();

        let mut read_state = Box::pin(ReadState {
            read,
            last_error: AtomicCell::new(None),
        });

        let input = context_factory(&mut read_state).map_err(|err| read_state.map_error(err))?;
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
                    break if let Some(err) = self.read_state.take_error() {
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

unsafe fn open_custom_io_input_context<R>(
    read: &mut Pin<Box<ReadState<R>>>,
    io_read: unsafe extern "C" fn(*mut c_void, *mut u8, c_int) -> c_int,
    io_seek: Option<unsafe extern "C" fn(*mut c_void, i64, c_int) -> i64>
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
            Some(io_read),
            None,
            io_seek,
        );

        let file_name = CString::new("input").unwrap();
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

unsafe extern "C" fn io_read<R: io::Read>(
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
            read_state.store_error(err);
            ffmpeg::Error::External.into()
        }
    }
}

unsafe extern "C" fn io_seek<R: io::Seek>(
    opaque: *mut c_void,
    offset: i64,
    whence: c_int,
) -> i64 {
    let read_state = unsafe { &mut *(opaque as *mut ReadState<R>) };

    let result = match whence {
        ffmpeg::ffi::AVSEEK_SIZE => stream_size(&mut read_state.read),
        ffmpeg::ffi::SEEK_CUR => read_state.read.seek(io::SeekFrom::Current(offset)),
        ffmpeg::ffi::SEEK_SET => read_state.read.seek(io::SeekFrom::Start(offset as u64)),
        ffmpeg::ffi::SEEK_END => read_state.read.seek(io::SeekFrom::End(offset)),
        _ => panic!("Unknown whence: {}", whence),
    };

    match result {
        Ok(result) => result as i64,
        Err(err) => {
            read_state.store_error(err);
            <ffmpeg::Error as Into<c_int>>::into(ffmpeg::Error::External) as i64
        }
    }
}

fn stream_size<R: io::Seek>(read: &mut R) -> io::Result<u64> {
    let old_position = read.stream_position()?;
    let size = read.seek(io::SeekFrom::End(0))?;
    if old_position != size {
        read.seek(io::SeekFrom::Start(old_position))?;
    }
    Ok(size)
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
