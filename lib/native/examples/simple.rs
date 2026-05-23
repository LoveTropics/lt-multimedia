use ffmpeg_next::format::sample::Type;
use ffmpeg_next::format::{Pixel, Sample};
use ffmpeg_next::ChannelLayout;
use multimedia::{AudioFrameFormat, FrameDecoder, Frames, MultimediaPacket, MultimediaReader, VideoFrameFormat};

pub fn main() {
    let path = std::env::args().skip(1).next().expect("Usage: example <video path>");
    println!("Loading video from: {}", path);

    let mut reader = MultimediaReader::open_path(path).expect("Failed to open video");

    println!("Duration: {:?}", reader.duration());

    let video_format = VideoFrameFormat::new(Pixel::ARGB, 1920, 1080);
    let audio_format = AudioFrameFormat::new(Sample::I16(Type::Packed), ChannelLayout::MONO, 44100);

    let mut video = reader.open_video_decoder()
        .expect("Failed to open video decoder")
        .expect("No video stream");
    let mut audio = reader.open_audio_decoder(audio_format)
        .expect("Failed to open audio decoder")
        .expect("No audio stream");

    let mut video_frame = vec![0u8; video_format.bytes().unwrap()];
    let mut audio_frame = Vec::new();

    while let Some(packet) = reader.read_packet() {
        let packet = packet.expect("Failed to read packet");
        match packet {
            MultimediaPacket::Video(packet) => {
                let mut frames = video.send_packet(packet).expect("Failed to decode video packet");
                for frame in frames.iter() {
                    let frame = frame.expect("Failed to decode video frame");
                    println!("V{:?}", frame.present_time());
                    frame.unpack_pixels(video_format, &mut video_frame).expect("Failed to unpack video frame");
                }
                video = frames.discard();
            }
            MultimediaPacket::Audio(packet) => {
                let mut frames = audio.send_packet(packet).expect("Failed to decode audio packet");
                for frame in frames.iter() {
                    let frame = frame.expect("Failed to decode audio frame");
                    println!("A{:?}", frame.present_time());
                    if audio_frame.len() < frame.bytes() {
                        audio_frame = vec![0u8; frame.bytes()];
                    }
                    frame.unpack_samples(&mut audio_frame).expect("Failed to unpack audio frame");
                }
                audio = frames.discard();
            }
        }
    }
}
