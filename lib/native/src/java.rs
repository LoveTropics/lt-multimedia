mod input;

use crate::*;
use ffmpeg_next::{format, ChannelLayout};
use input::JInputStream;
use jni::objects::{JByteBuffer, JClass, JObject, JString};
use jni::sys::{jboolean, jdouble, jint, jlong};
use jni::JNIEnv;
use std::ffi::CStr;
use std::{mem, slice};

fn handle_result<R>(
    mut env: JNIEnv,
    result: Result<R, Error>,
    default: R
) -> R {
    match result {
        Ok(result) => result,
        Err(Error::Io(err)) => {
            match env.exception_check() {
                // A Java exception has already been thrown, no action needed
                Ok(true) => {},
                _ => {
                    env.throw_new(
                        "java/io/IOException",
                        format!("{:?}", err)
                    ).expect("Failed to throw exception");
                }
            }
            default
        },
        Err(err) => {
            env.throw_new(
                "org/lovetropics/multimedia/DecoderException",
                format!("{:?}", err)
            ).expect("Failed to throw exception");
            default
        }
    }
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_openReader<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    file_name: JString<'a>,
    input: JObject<'a>,
) -> jlong {
    let file_name = env.get_string(&file_name).unwrap();
    let file_name = unsafe { CStr::from_ptr(file_name.as_ptr()) };
    let file_name = file_name.to_string_lossy();

    let input = JInputStream::new(&mut env, input);
    handle_result(
        env,
        MultimediaReader::open(file_name, input).map(into_java_ptr),
        0
    )
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_destroyReader<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    reader: jlong,
) {
    unsafe { destroy_java_ptr::<MultimediaReader<JInputStream>>(reader) }
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_readPacket<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    reader: jlong,
) -> jlong {
    let reader: &mut MultimediaReader<JInputStream> = unsafe { from_java_ptr(&env, reader) };
    match reader.read_packet() {
        Some(packet) => handle_result(env, packet.map(into_java_ptr), 0),
        None => 0
    }
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_destroyPacket<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    packet: jlong,
) {
    unsafe { destroy_java_ptr::<JMultimediaPacket>(packet) }
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_getPacketType<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    packet: jlong,
) -> jint {
    let packet: &mut JMultimediaPacket = unsafe { from_java_ptr(&env, packet) };
    match &packet.0 {
        Some(MultimediaPacket::Video(_)) => JPacketType::Video as jint,
        Some(MultimediaPacket::Audio(_)) => JPacketType::Audio as jint,
        None => JPacketType::Unknown as jint,
    }
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_openVideoDecoder<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    reader: jlong,
) -> jlong {
    let reader: &mut MultimediaReader<JInputStream> = unsafe { from_java_ptr(&env, reader) };
    let decoder = reader.open_video_decoder().map(|decoder|
        decoder.map(|decoder| into_java_ptr(JFrameDecoder::from(decoder)))
            .unwrap_or(0)
    );
    handle_result(env, decoder, 0)
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_destroyVideoDecoder<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    decoder: jlong,
) {
    unsafe { destroy_java_ptr::<JFrameDecoder<VideoDecoder>>(decoder) }
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_getVideoWidth<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    decoder: jlong,
) -> jint {
    let decoder: &mut JFrameDecoder<VideoDecoder> = unsafe { from_java_ptr(&env, decoder) };
    decoder.format().width() as jint
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_getVideoHeight<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    decoder: jlong,
) -> jint {
    let decoder: &mut JFrameDecoder<VideoDecoder> = unsafe { from_java_ptr(&env, decoder) };
    decoder.format().height() as jint
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_sendVideoPacket<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    decoder: jlong,
    packet: jlong,
) {
    let decoder: &mut JFrameDecoder<VideoDecoder> = unsafe { from_java_ptr(&env, decoder) };
    let result = decoder.send_video_packet(unsafe { from_java_ptr(&env, packet) });
    handle_result(env, result, ());
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_readVideoFrame<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    decoder: jlong,
) -> jlong {
    let decoder: &mut JFrameDecoder<VideoDecoder> = unsafe { from_java_ptr(&env, decoder) };
    let result = decoder.read_frame().map(|frame|
        frame.map(into_java_ptr).unwrap_or(0)
    );
    handle_result(env, result, 0)
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_getVideoFramePresentTime<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    frame: jlong,
) -> jdouble {
    let frame: &mut VideoFrame = unsafe { from_java_ptr(&env, frame) };
    frame.present_time()
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_getVideoFramePresentEndTime<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    frame: jlong,
) -> jdouble {
    let frame: &mut VideoFrame = unsafe { from_java_ptr(&env, frame) };
    frame.present_end_time()
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_unpackVideoPixels<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    frame: jlong,
    width: jint,
    height: jint,
    output: JByteBuffer,
    offset: jint,
) -> jint {
    let frame: Box<VideoFrame> = unsafe { take_java_ptr(frame) };
    let output = unsafe { as_slice_with_offset_mut(&env, &output, offset) };
    let format = VideoFrameFormat::new(format::Pixel::RGBA, width as u32, height as u32);
    let result = frame.unpack_pixels(format, output);
    handle_result(env, result, 0) as jint
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_destroyVideoFrame<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    frame: jlong,
) {
    unsafe { destroy_java_ptr::<VideoFrame>(frame) }
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_openAudioDecoder<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    reader: jlong,
    sample_format: jint,
    stereo: jboolean,
    sample_rate: jint
) -> jlong {
    let sample_format = match sample_format {
        0 => format::Sample::U8(format::sample::Type::Packed),
        1 => format::Sample::I16(format::sample::Type::Packed),
        2 => format::Sample::I32(format::sample::Type::Packed),
        3 => format::Sample::I64(format::sample::Type::Packed),
        4 => format::Sample::F32(format::sample::Type::Packed),
        5 => format::Sample::F64(format::sample::Type::Packed),
        _ => panic!("Unsupported audio format: {}", sample_format),
    };

    let reader: &mut MultimediaReader<JInputStream> = unsafe { from_java_ptr(&env, reader) };
    let format = AudioFrameFormat::new(
        sample_format,
        if stereo != 0 { ChannelLayout::STEREO } else { ChannelLayout::MONO },
        sample_rate as u32
    );

    let decoder = reader.open_audio_decoder(format).map(|decoder|
        decoder.map(|decoder| into_java_ptr(JFrameDecoder::from(decoder)))
            .unwrap_or(0)
    );
    handle_result(env, decoder, 0)
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_destroyAudioDecoder<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    decoder: jlong,
) {
    unsafe { destroy_java_ptr::<JFrameDecoder<AudioDecoder>>(decoder) }
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_sendAudioPacket<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    decoder: jlong,
    packet: jlong,
) {
    let decoder: &mut JFrameDecoder<AudioDecoder> = unsafe { from_java_ptr(&env, decoder) };
    let result = decoder.send_audio_packet(unsafe { from_java_ptr(&env, packet) });
    handle_result(env, result, ());
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_readAudioFrame<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    decoder: jlong,
) -> jlong {
    let decoder: &mut JFrameDecoder<AudioDecoder> = unsafe { from_java_ptr(&env, decoder) };
    let result = decoder.read_frame().map(|frame|
        frame.map(into_java_ptr).unwrap_or(0)
    );
    handle_result(env, result, 0)
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_getAudioFramePresentTime<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    frame: jlong,
) -> jdouble {
    let frame: &mut AudioFrame = unsafe { from_java_ptr(&env, frame) };
    frame.present_time()
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_getAudioFrameSamples<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    frame: jlong,
) -> jint {
    let frame: &mut AudioFrame = unsafe { from_java_ptr(&env, frame) };
    frame.samples() as jint
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_getAudioFrameBytes<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    frame: jlong,
) -> jint {
    let frame: &mut AudioFrame = unsafe { from_java_ptr(&env, frame) };
    frame.bytes() as jint
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_unpackAudioSamples<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    frame: jlong,
    output: JByteBuffer,
    offset: jint,
) {
    let frame: Box<AudioFrame> = unsafe { take_java_ptr(frame) };
    let output = unsafe { as_slice_with_offset_mut(&env, &output, offset) };
    let result = frame.unpack_samples(output);
    handle_result(env, result, ());
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "system" fn Java_org_lovetropics_multimedia_MultimediaNative_destroyAudioFrame<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    frame: jlong,
) {
    unsafe { destroy_java_ptr::<AudioFrame>(frame) }
}

unsafe fn from_java_ptr<'a, T>(_: &'a JNIEnv<'a>, ptr: jlong) -> &'a mut T {
    let ptr = ptr as *mut T;
    unsafe { &mut *ptr }
}

unsafe fn destroy_java_ptr<T>(ptr: jlong) {
    drop(unsafe { take_java_ptr::<T>(ptr) })
}

unsafe fn take_java_ptr<T>(ptr: jlong) -> Box<T> {
    unsafe { Box::from_raw(ptr as *mut T) }
}

fn into_java_ptr<T>(value: T) -> jlong {
    Box::leak(Box::new(value)) as *mut T as jlong
}

unsafe fn as_slice_with_offset_mut<'a>(env: &'a JNIEnv<'a>, buffer: &'a JByteBuffer, offset: jint) -> &'a mut [u8] {
    let buffer = unsafe { as_slice_mut(&env, &buffer) };
    &mut buffer[offset as usize..]
}

unsafe fn as_slice_mut<'a>(env: &'a JNIEnv<'a>, buffer: &'a JByteBuffer) -> &'a mut [u8] {
    unsafe {
        slice::from_raw_parts_mut(
            env.get_direct_buffer_address(&buffer).unwrap(),
            env.get_direct_buffer_capacity(&buffer).unwrap(),
        )
    }
}

struct JMultimediaPacket(Option<MultimediaPacket>);

#[repr(u8)]
enum JPacketType {
    Video = 0,
    Audio = 1,
    Unknown = 2,
}

enum JFrameDecoder<D: FrameDecoder> {
    WaitForPacket(D),
    DrainFrames(D::Frames),
    Closed,
}

impl<D: FrameDecoder> From<D> for JFrameDecoder<D> {
    fn from(decoder: D) -> Self {
        JFrameDecoder::WaitForPacket(decoder)
    }
}

impl JFrameDecoder<VideoDecoder> {
    fn format(&self) -> VideoFrameFormat {
        match self {
            JFrameDecoder::WaitForPacket(decoder) => decoder.format(),
            JFrameDecoder::DrainFrames(frames) => frames.decoder().format(),
            JFrameDecoder::Closed => panic!("Decoder already closed"),
        }
    }

    fn send_video_packet(&mut self, packet: &mut JMultimediaPacket) -> Result<()> {
        self.send_packet(match packet.0.take() {
            Some(MultimediaPacket::Video(packet)) => packet,
            _ => panic!("Got wrong packet type"),
        })
    }
}

impl JFrameDecoder<AudioDecoder> {
    fn send_audio_packet(&mut self, packet: &mut JMultimediaPacket) -> Result<()> {
        self.send_packet(match packet.0.take() {
            Some(MultimediaPacket::Audio(packet)) => packet,
            _ => panic!("Got wrong packet type"),
        })
    }
}

impl<D: FrameDecoder> JFrameDecoder<D> {
    fn send_packet(&mut self, packet: D::Packet) -> Result<()> {
        match mem::replace(self, JFrameDecoder::Closed) {
            JFrameDecoder::WaitForPacket(decoder) => {
                let frames = decoder.send_packet(packet)?;
                *self = JFrameDecoder::DrainFrames(frames);
                Ok(())
            }
            _ => panic!("Decoder not expecting packets"),
        }
    }

    fn read_frame(&mut self) -> Result<Option<D::Frame>> {
        match mem::replace(self, JFrameDecoder::Closed) {
            JFrameDecoder::WaitForPacket(decoder) => {
                *self = JFrameDecoder::WaitForPacket(decoder);
                Ok(None)
            },
            JFrameDecoder::DrainFrames(mut frames) => {
                let frame = frames.iter().next();
                match frame {
                    Some(Ok(frame)) => {
                        *self = JFrameDecoder::DrainFrames(frames);
                        Ok(Some(frame))
                    }
                    None => {
                        *self = JFrameDecoder::WaitForPacket(frames.discard());
                        Ok(None)
                    }
                    Some(Err(err)) => Err(err)
                }
            },
            _ => panic!("Decoder already closed"),
        }
    }
}
