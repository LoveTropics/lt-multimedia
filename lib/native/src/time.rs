use ffmpeg_next as ffmpeg;
use std::time::Duration;

pub const AV_TIME_BASE: ffmpeg::Rational = ffmpeg::Rational(1, ffmpeg::ffi::AV_TIME_BASE as i32);

#[inline]
pub const fn to_duration(time: i64, time_base: ffmpeg::Rational) -> Duration {
    if time_base.0 == 1 && time_base.1 == 1000_000 {
        Duration::from_micros(time as u64)
    } else {
        Duration::from_micros((time * time_base.0 as i64 * 1000_000 / time_base.1 as i64) as u64)
    }
}

#[inline]
pub const fn from_duration(time: Duration, time_base: ffmpeg::Rational) -> i64 {
    if time_base.0 == 1 && time_base.1 == 1000_000 {
        time.as_micros() as i64
    } else {
        (time.as_micros() as i64 * time_base.1 as i64) / (1000_000 * time_base.0 as i64)
    }
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
