FILE(GLOB MNN_OpenGL_SRC ${CMAKE_CURRENT_LIST_DIR}/*.cpp)
# A developer worktree may contain the ignored legacy generated file. Never add
# it alongside the reproducible build-tree output below.
list(FILTER MNN_OpenGL_SRC EXCLUDE REGEX "/AllShader\\.cpp$")

option(MNN_OPENGL_REGEN "Regenerate OpenGL Shaders." ON)
set(MNN_OPENGL_SHADER_SOURCE "${CMAKE_CURRENT_LIST_DIR}/AllShader.cpp")

IF(MNN_OPENGL_REGEN)
  if(NOT PYTHON_EXECUTABLE)
    find_package(Python3 COMPONENTS Interpreter REQUIRED)
    set(PYTHON_EXECUTABLE "${Python3_EXECUTABLE}")
  endif()

  set(MNN_OPENGL_GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
  set(MNN_OPENGL_GENERATED_HEADER "${MNN_OPENGL_GENERATED_DIR}/AllShader.hpp")
  set(MNN_OPENGL_SHADER_SOURCE "${MNN_OPENGL_GENERATED_DIR}/AllShader.cpp")
  FILE(GLOB_RECURSE MNN_OPENGL_SHADER_FILES CONFIGURE_DEPENDS "${CMAKE_CURRENT_LIST_DIR}/glsl/*.glsl")

  add_custom_command(
    OUTPUT "${MNN_OPENGL_GENERATED_HEADER}" "${MNN_OPENGL_SHADER_SOURCE}"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${MNN_OPENGL_GENERATED_DIR}"
    COMMAND "${PYTHON_EXECUTABLE}"
      "${CMAKE_CURRENT_LIST_DIR}/makeshader.py"
      "glsl/"
      "${MNN_OPENGL_GENERATED_HEADER}"
      "${MNN_OPENGL_SHADER_SOURCE}"
    DEPENDS "${CMAKE_CURRENT_LIST_DIR}/makeshader.py" ${MNN_OPENGL_SHADER_FILES}
    WORKING_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}"
    COMMENT "Generating MNN OpenGL shaders"
    VERBATIM
  )
  add_custom_target(MNN_GLCodeGen DEPENDS "${MNN_OPENGL_GENERATED_HEADER}" "${MNN_OPENGL_SHADER_SOURCE}")
ELSEIF(NOT EXISTS "${MNN_OPENGL_SHADER_SOURCE}")
  message(FATAL_ERROR "MNN OpenGL requires AllShader.cpp; enable MNN_OPENGL_REGEN for a fresh checkout")
ENDIF()

IF(MNN_SEP_BUILD)
    if (MNN_BUILD_FOR_ANDROID_COMMAND)
        set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "../../../")
    endif()
    add_library(MNN_GL SHARED ${MNN_OpenGL_SRC} "${MNN_OPENGL_SHADER_SOURCE}")
    target_link_libraries(MNN_GL MNN GLESv3 EGL)
ELSE()
    add_library(MNN_GL OBJECT ${MNN_OpenGL_SRC} "${MNN_OPENGL_SHADER_SOURCE}")
ENDIF()
target_include_directories(MNN_GL PRIVATE
  $<$<BOOL:${MNN_OPENGL_REGEN}>:${MNN_OPENGL_GENERATED_DIR}>
  ${CMAKE_CURRENT_LIST_DIR}/
)
IF(MNN_OPENGL_REGEN)
  add_dependencies(MNN_GL MNN_GLCodeGen)
ENDIF()
