cmake_minimum_required(VERSION 3.22.1)
project(deploy_fixture C ASM)
add_library(deploy_fixture SHARED fixture.c patchpoint.S)
target_compile_options(deploy_fixture PRIVATE -O1)
