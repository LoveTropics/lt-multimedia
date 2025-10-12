use jni::objects::{GlobalRef, JByteArray, JMethodID, JObject};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jbyte, jint, jsize, jvalue};
use jni::{JNIEnv, JavaVM};
use std::{io, slice};

pub struct JInputStream {
    vm: JavaVM,
    object: GlobalRef,
    read_method: JMethodID,
    close_method: JMethodID,
    buffer: Option<(GlobalRef, usize)>,
}

impl JInputStream {
    pub fn new<'a>(env: &mut JNIEnv<'a>, object: JObject<'a>) -> Self {
        let object = env.new_global_ref(object).unwrap();

        let class = env.find_class("java/io/InputStream").unwrap();
        let read_method = env.get_method_id(&class, "read", "([BII)I").unwrap();
        let close_method = env.get_method_id(&class, "close", "()V").unwrap();

        JInputStream {
            vm: env.get_java_vm().unwrap(),
            object,
            read_method,
            close_method,
            buffer: None,
        }
    }

    fn ensure_buffer_capacity(
        env: &mut JNIEnv,
        buffer: Option<(GlobalRef, usize)>,
        expected_capacity: usize,
    ) -> (GlobalRef, usize) {
        match buffer {
            Some((buffer, capacity)) if capacity >= expected_capacity => (buffer, capacity),
            _ => (
                env.new_global_ref(env.new_byte_array(expected_capacity as jsize).unwrap()).unwrap(),
                expected_capacity,
            ),
        }
    }
}

impl io::Read for JInputStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let mut env = self.vm.get_env().unwrap();

        self.buffer = Some(Self::ensure_buffer_capacity(
            &mut env,
            self.buffer.take(),
            buf.len(),
        ));
        let java_buf: &JByteArray = self.buffer.as_ref().unwrap().0.as_obj().into();

        let result = unsafe {
            env.call_method_unchecked(
                &self.object,
                self.read_method,
                ReturnType::Primitive(Primitive::Int),
                &[
                    jvalue { l: java_buf.as_raw(), },
                    jvalue { i: 0 },
                    jvalue { i: buf.len() as jint, },
                ],
            )
            .map(|v| v.i().unwrap())
        };

        match result {
            Ok(read_bytes) if read_bytes >= 0 => {
                let read_bytes = read_bytes as usize;
                assert!(
                    read_bytes <= buf.len(),
                    "Read more bytes than can fit in buffer ({} > {})",
                    read_bytes,
                    buf.len()
                );

                env.get_byte_array_region(java_buf, 0, unsafe {
                    slice::from_raw_parts_mut(buf.as_mut_ptr() as *mut jbyte, read_bytes)
                })
                .expect("Unable to copy from Java byte array");

                Ok(read_bytes)
            }
            Ok(_) => Ok(0),
            Err(jni::errors::Error::JavaException) => {
                Err(io::Error::other(jni::errors::Error::JavaException))
            }
            Err(err) => panic!("Unable to call InputStream.read(): {}", err),
        }
    }
}

impl Drop for JInputStream {
    fn drop(&mut self) {
        let mut env = self
            .vm
            .get_env()
            .expect("Cannot close InputStream on this thread");
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
            Err(err) => panic!("Unable to call InputStream.close(): {}", err),
        }
    }
}
