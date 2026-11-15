#!/usr/bin/env bash
set -euo pipefail

# Run with Git Bash on Windows. Place the three source archives in a fresh work directory.
work_dir=$(cd "${1:?Pass the directory containing the source archives}" && pwd)
ndk=$(cd "${2:?Pass the path to Android NDK 27.0.12077973}" && pwd)
cmake_bin=$(cd "${3:?Pass the Android SDK path to cmake/3.22.1/bin}" && pwd)
toolchain="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
make_dir="$ndk/prebuilt/windows-x86_64/bin"
export PATH="$toolchain:$make_dir:$PATH"
compiler="clang --target=aarch64-linux-android26"
work_windows=$(cygpath -am "$work_dir")
path_flags="-ffile-prefix-map=$work_windows=. -ffile-prefix-map=$work_dir=."

cd "$work_dir"
sha256sum -c - <<'CHECKSUMS'
de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f  ffmpeg-7.1.5.tar.xz
65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1  opus-1.5.2.tar.gz
ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e  lame_3.100.orig.tar.gz
CHECKSUMS
if [[ ! -d ffmpeg-7.1.5 ]]; then
  tar -xf ffmpeg-7.1.5.tar.xz
fi
if [[ ! -d opus-1.5.2 ]]; then
  tar -xf opus-1.5.2.tar.gz
fi
if [[ ! -d lame-3.100 ]]; then
  tar -xf lame_3.100.orig.tar.gz
fi

"$cmake_bin/cmake.exe" -S opus-1.5.2 -B opus-arm64 -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$(cygpath -am "$cmake_bin/ninja.exe")" \
  -DCMAKE_TOOLCHAIN_FILE="$(cygpath -am "$ndk/build/cmake/android.toolchain.cmake")" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="$path_flags" \
  -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF
"$cmake_bin/cmake.exe" --build opus-arm64 --parallel 2

(
  cd lame-3.100
  CC="$compiler" AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip CFLAGS="-O2 -fPIC $path_flags" \
    ./configure --host=aarch64-linux-android --build=x86_64-w64-mingw32 \
    --disable-shared --enable-static --disable-frontend --disable-decoder --disable-gtktest --disable-nasm
  "$make_dir/make.exe" -j2 -C libmp3lame
)

mkdir -p dependencies/include/lame build-arm64-codecs
cp lame-3.100/include/lame.h dependencies/include/lame/
cd build-arm64-codecs

# Expose the locally built libopus to FFmpeg's configure check in Git Bash.
cat > opus-pkg-config.sh <<'PKG_CONFIG'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  '--version') printf '%s\n' '1.0.0' ;;
  '--exists --print-errors opus') test -r ../opus-arm64/libopus.a ;;
  '--cflags opus') printf '%s\n' '-I../opus-1.5.2/include' ;;
  '--libs opus') printf '%s\n' '../opus-arm64/libopus.a -lm' ;;
  '--variable=includedir opus') printf '%s\n' '../opus-1.5.2/include' ;;
  *) exit 1 ;;
esac
PKG_CONFIG
chmod +x opus-pkg-config.sh

../ffmpeg-7.1.5/configure \
  --target-os=android --arch=aarch64 --enable-cross-compile \
  --cc="$compiler" --host-cc="$compiler" \
  --ar=llvm-ar --nm=llvm-nm --ranlib=llvm-ranlib --strip=llvm-strip \
  --enable-gpl --enable-version3 --disable-autodetect --disable-asm --enable-zlib \
  --disable-doc --disable-debug --disable-network --disable-ffplay \
  --disable-postproc --disable-everything \
  --enable-protocol=file,pipe \
  --enable-indev=lavfi \
  --enable-demuxer=aac,ffmetadata,flac,image2,image2pipe,image_jpeg_pipe,image_png_pipe,matroska,mov,mp3,mpegts,ogg,wav \
  --enable-muxer=adts,hash,ipod,matroska,mov,mp3,mp4,ogg,opus,pcm_s16le,wav,webm \
  --enable-decoder=aac,alac,flac,mjpeg,mp3,opus,png,vorbis \
  --enable-decoder=pcm_u8,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le,pcm_alaw,pcm_mulaw \
  --enable-encoder=aac,libmp3lame,libopus,mjpeg,pcm_s16le \
  --enable-filter=aresample,anull,anullsrc,aformat,format,scale \
  --enable-parser=aac,mjpeg,mpegaudio,opus,png,vorbis \
  --enable-libmp3lame --enable-libopus \
  --pkg-config=./opus-pkg-config.sh \
  --extra-cflags=-I../dependencies/include \
  --extra-ldflags="-Wl,-z,max-page-size=16384 -L../lame-3.100/libmp3lame/.libs"

# Configure writes MSYS paths, while the NDK's make runs natively on Windows.
source_msys="$work_dir/ffmpeg-7.1.5"
source_windows=$(cygpath -am "$source_msys")
SOURCE_MSYS="$source_msys" SOURCE_WINDOWS="$source_windows" perl -pi -e \
  's/\Q$ENV{SOURCE_MSYS}\E/$ENV{SOURCE_WINDOWS}/g' Makefile ffbuild/config.mak
# Keep the physical path mappings out of the configuration printed by FFmpeg.
printf '\nCFLAGS += %s\n' "$path_flags" >> ffbuild/config.mak
"$make_dir/make.exe" -j2 ffmpeg ffprobe

work_backslashes=$(cygpath -aw "$work_dir")
if LC_ALL=C grep -aiFq -e "$work_windows/" -e "$work_backslashes\\" -e "$work_dir/" ffmpeg ffprobe; then
  echo 'A native binary still contains the local build path.' >&2
  exit 1
fi
