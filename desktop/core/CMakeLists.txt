cmake_minimum_required(VERSION 3.18)
project(airplay-core C)

# Built with MSYS2 MinGW-w64: pacman -S mingw-w64-x86_64-{gcc,cmake,ninja,openssl,libplist,pkgconf}
set(CMAKE_C_STANDARD 11)

set(UX ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/uxplay)

file(GLOB UX_SRC ${UX}/*.c)
set(LLHTTP_SRC ${UX}/llhttp/api.c ${UX}/llhttp/http.c ${UX}/llhttp/llhttp.c)
set(PLAYFAIR_SRC
    ${UX}/playfair/hand_garble.c ${UX}/playfair/modified_md5.c ${UX}/playfair/omg_hax.c
    ${UX}/playfair/playfair.c ${UX}/playfair/sap_hash.c)

add_executable(airplay-core airplay_cli.c ${UX_SRC} ${LLHTTP_SRC} ${PLAYFAIR_SRC})

target_include_directories(airplay-core PRIVATE ${UX} ${UX}/llhttp ${UX}/playfair)
target_compile_definitions(airplay-core PRIVATE
    NOHOLD STANDALONE TARGET_POSIX _REENTRANT WIN32 _WIN32
    OPENSSL_API_COMPAT=0x10101000L PLIST_210 PLIST_230 LIBPLIST_STATIC)
target_compile_options(airplay-core PRIVATE -O2 -w
    -Wno-error=implicit-function-declaration -Wno-error=int-conversion -Wno-error=incompatible-pointer-types)

find_package(PkgConfig REQUIRED)
pkg_check_modules(PLIST REQUIRED libplist-2.0)
set(OPENSSL_USE_STATIC_LIBS TRUE)
find_package(OpenSSL REQUIRED)

target_include_directories(airplay-core PRIVATE ${PLIST_INCLUDE_DIRS})
target_link_libraries(airplay-core PRIVATE
    -static
    ${PLIST_STATIC_LDFLAGS}
    OpenSSL::Crypto
    pthread ws2_32 iphlpapi wsock32 crypt32 advapi32)
