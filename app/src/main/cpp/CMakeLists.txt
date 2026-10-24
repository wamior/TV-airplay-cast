cmake_minimum_required(VERSION 3.18)
project(tvcastnative C)

set(CMAKE_C_STANDARD 11)

find_package(openssl REQUIRED CONFIG)

set(PLIST ${CMAKE_CURRENT_SOURCE_DIR}/libplist)
set(UX ${CMAKE_CURRENT_SOURCE_DIR}/uxplay)

file(GLOB UX_SRC ${UX}/*.c)
set(PLIST_SRC
    ${PLIST}/src/base64.c ${PLIST}/src/bplist.c ${PLIST}/src/bytearray.c ${PLIST}/src/hashtable.c
    ${PLIST}/src/jplist.c ${PLIST}/src/jsmn.c ${PLIST}/src/oplist.c ${PLIST}/src/out-default.c
    ${PLIST}/src/out-limd.c ${PLIST}/src/out-plutil.c ${PLIST}/src/plist.c ${PLIST}/src/ptrarray.c
    ${PLIST}/src/time64.c ${PLIST}/src/xplist.c
    ${PLIST}/libcnary/node.c ${PLIST}/libcnary/node_list.c)
set(LLHTTP_SRC ${UX}/llhttp/api.c ${UX}/llhttp/http.c ${UX}/llhttp/llhttp.c)
set(PLAYFAIR_SRC
    ${UX}/playfair/hand_garble.c ${UX}/playfair/modified_md5.c ${UX}/playfair/omg_hax.c
    ${UX}/playfair/playfair.c ${UX}/playfair/sap_hash.c)

add_library(tvcastnative SHARED
    airplay_jni.c ${UX_SRC} ${LLHTTP_SRC} ${PLAYFAIR_SRC} ${PLIST_SRC})

target_include_directories(tvcastnative PRIVATE
    ${UX} ${UX}/llhttp ${UX}/playfair
    ${PLIST}/include ${PLIST}/src ${PLIST}/libcnary/include)

target_compile_definitions(tvcastnative PRIVATE
    NOHOLD STANDALONE TARGET_POSIX _REENTRANT
    OPENSSL_API_COMPAT=0x10101000L
    PLIST_210 PLIST_230 LIBPLIST_STATIC
    PACKAGE_VERSION="2.6.0"
    HAVE_STRNDUP HAVE_MEMMEM HAVE_STRPTIME HAVE_TIMEGM HAVE_GMTIME_R HAVE_LOCALTIME_R
    HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE HAVE_ATTRIBUTE_CONSTRUCTOR
    __LITTLE_ENDIAN__=1)

# The protocol code is old gcc-era C: keep clang from rejecting it over implicit declarations and the like.
target_compile_options(tvcastnative PRIVATE
    -O2 -w
    -Wno-error=implicit-function-declaration -Wno-error=int-conversion
    -Wno-error=incompatible-pointer-types -Wno-error=deprecated-declarations)

target_link_libraries(tvcastnative PRIVATE openssl::crypto log)
