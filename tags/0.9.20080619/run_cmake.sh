LIB_SUFFIX=

test -d /lib64 && LIB_SUFFIX=64

cmake -DCMAKE_BUILD_TYPE=RelWithDebInfo \
      -DLIB_SUFFIX=$LIB_SUFFIX \
      -DCMAKE_INSTALL_PREFIX=/usr \
      -DSYSCONF_INSTALL_DIR=/etc \
      -DLOCALSTATE_INSTALL_PREFIX=/var \
      -DSHAREDSTATE_INSTALL_PREFIX=/var \
    $(dirname $0)
