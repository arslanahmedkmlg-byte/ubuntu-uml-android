cmake_minimum_required(VERSION 3.22.1)
project(ubuntu_uml_native C)

add_library(pty_bridge SHARED pty_bridge.c)
target_link_libraries(pty_bridge log)

add_executable(uml-net uml_net.c)
target_compile_options(uml-net PRIVATE -fno-emulated-tls)
target_link_options(uml-net PRIVATE -static -fno-emulated-tls)

set_target_properties(uml-net PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "uml-net"
    SUFFIX ".so"
)
