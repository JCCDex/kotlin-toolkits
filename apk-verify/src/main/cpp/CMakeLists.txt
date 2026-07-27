cmake_minimum_required(VERSION 3.22)
project("integrity" C)

add_library(integrity SHARED integrity.c)

target_compile_features(integrity PUBLIC c_std_99)

# 16 KB page size alignment (required for Android 15+ / Google Play Nov 2025)
target_link_options(integrity PRIVATE -Wl,-z,max-page-size=16384)
