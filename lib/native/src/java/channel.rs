use jni::objects::{GlobalRef, JMethodID, JObject};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jlong, jvalue};
use jni::{JNIEnv, JavaVM};
use std::io;
use std::io::SeekFrom;

pub struct JReadableByteChannel {
    vm: JavaVM,
    object: GlobalRef,
    read_method: JMethodID,
    close_method: JMethodID,
}

impl JReadableByteChannel {
    pub fn new<'a>(env: &mut JNIEnv<'a>, object: JObject<'a>) -> Result<Self, jni::errors::Error> {
        let object = env.new_global_ref(object)?;

        let class = env.find_class("java/nio/channels/ReadableByteChannel")?;
        let read_method = env.get_method_id(&class, "read", "(Ljava/nio/ByteBuffer;)I")?;
        let close_method = env.get_method_id(&class, "close", "()V")?;

        Ok(JReadableByteChannel {
            vm: env.get_java_vm()?,
            object,
            read_method,
            close_method,
        })
    }
}

impl io::Read for JReadableByteChannel {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let mut env = self.vm.get_env().unwrap();

        let java_buf = unsafe { env.new_direct_byte_buffer(buf.as_mut_ptr(), buf.len()) }.unwrap();

        let result = unsafe {
            env.call_method_unchecked(
                &self.object,
                self.read_method,
                ReturnType::Primitive(Primitive::Int),
                &[jvalue {
                    l: java_buf.as_raw(),
                }],
            )
            .map(|v| v.i().unwrap())
        };

        match result {
            Ok(read_bytes) if read_bytes >= 0 => Ok(read_bytes as usize),
            Ok(_) => Ok(0),
            Err(jni::errors::Error::JavaException) => {
                Err(io::Error::other(jni::errors::Error::JavaException))
            }
            Err(err) => panic!("Unable to call SeekableByteChannel.read(): {}", err),
        }
    }
}

impl Drop for JReadableByteChannel {
    fn drop(&mut self) {
        let mut env = self
            .vm
            .get_env()
            .expect("Cannot close ReadableByteChannel on this thread");
        let result = unsafe {
            env.call_method_unchecked(
                &self.object,
                self.close_method,
                ReturnType::Primitive(Primitive::Void),
                &[],
            )
        };
        match result {
            Ok(_) => (),
            // The Java exception will be propagated already, no need to panic
            Err(jni::errors::Error::JavaException) => (),
            Err(err) => panic!("Unable to call ReadableByteChannel.close(): {}", err),
        }
    }
}

pub struct JSeekableByteChannel {
    inner: JReadableByteChannel,
    set_position_method: JMethodID,
    position: u64,
    size: u64,
}

impl JSeekableByteChannel {
    pub fn new<'a>(env: &mut JNIEnv<'a>, object: JObject<'a>) -> Result<Self, jni::errors::Error> {
        let inner = JReadableByteChannel::new(env, object)?;

        let class = env.find_class("java/nio/channels/SeekableByteChannel")?;
        let set_position_method = env.get_method_id(
            &class,
            "position",
            "(J)Ljava/nio/channels/SeekableByteChannel;",
        )?;

        let position = env
            .call_method(&inner.object, "position", "()J", &[])
            .map(|v| v.j().unwrap())? as u64;
        let size = env
            .call_method(&inner.object, "size", "()J", &[])
            .map(|v| v.j().unwrap())? as u64;

        Ok(JSeekableByteChannel {
            inner,
            set_position_method,
            position,
            size,
        })
    }
}

impl io::Read for JSeekableByteChannel {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let result = self.inner.read(buf);
        if let Ok(read_bytes) = result {
            self.position += read_bytes as u64;
        }
        result
    }
}

impl io::Seek for JSeekableByteChannel {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let mut env = self.inner.vm.get_env().unwrap();

        let new_position = match pos {
            SeekFrom::Start(offset) => offset,
            SeekFrom::End(offset) => u64::try_from(self.size as i64 + offset)
                .map_err(|_| io::Error::other("Cannot seek before byte 0"))?,
            SeekFrom::Current(offset) => u64::try_from(self.position as i64 + offset)
                .map_err(|_| io::Error::other("Cannot seek before byte 0"))?,
        };

        // SeekFrom::Current(0) is used to fetch the current position
        if new_position == self.position {
            return Ok(new_position);
        }

        let result = unsafe {
            env.call_method_unchecked(
                &self.inner.object,
                self.set_position_method,
                ReturnType::Object,
                &[jvalue {
                    j: new_position as jlong,
                }],
            )
        };

        match result {
            Ok(_) => {
                self.position = new_position;
                Ok(new_position)
            }
            Err(jni::errors::Error::JavaException) => {
                Err(io::Error::other(jni::errors::Error::JavaException))
            }
            Err(err) => panic!("Unable to call SeekableByteChannel.position(): {}", err),
        }
    }
}
