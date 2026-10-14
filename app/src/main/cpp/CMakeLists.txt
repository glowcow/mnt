# The DjVu reader: DjVuLibre's library and the app's JNI bridge to it, in one shared library.
cmake_minimum_required(VERSION 3.22)
project(mntdjvu CXX)

file(GLOB DJVULIBRE djvulibre/*.cpp)
add_library(mntdjvu SHARED djvu.cpp ${DJVULIBRE})
target_include_directories(mntdjvu PRIVATE djvulibre)
target_compile_definitions(mntdjvu PRIVATE HAVE_CONFIG_H=1)
target_compile_options(mntdjvu PRIVATE -O2 -fexceptions -frtti -ffunction-sections -fdata-sections -w)
target_link_options(mntdjvu PRIVATE -Wl,--gc-sections)
target_link_libraries(mntdjvu jnigraphics)
