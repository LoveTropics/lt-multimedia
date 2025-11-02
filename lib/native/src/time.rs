use ffmpeg_next as ffmpeg;
use std::time::Duration;

#[inline]
pub const fn to_duration(time: i64, time_base: ffmpeg::Rational) -> Duration {
    Duration::from_micros((time * time_base.0 as i64 * 1000_000 / time_base.1 as i64) as u64)
}

pub fn frame_present_time(frame: &ffmpeg::Frame, time_base: ffmpeg::Rational) -> Duration {
    let present_time = frame
        .timestamp()
        .or(frame.pts())
        .unwrap_or(frame.packet().dts);
    to_duration(present_time, time_base)
}

pub fn frame_present_duration(
    frame: &ffmpeg::Frame,
    time_base: ffmpeg::Rational,
) -> Option<Duration> {
    let present_duration = unsafe { (*frame.as_ptr()).duration };
    match present_duration {
        0 => None,
        _ => Some(to_duration(present_duration, time_base)),
    }
}
