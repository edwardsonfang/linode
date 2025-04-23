function stat_test_cleanup() {
  md5sum ./tmp_test
  ./tmp_test
  rm ./tmp_test
}

function tyo_apr() {
  echo tyo_apr
  gcc $1 \
    -fdiagnostics-color=always \
    -DACCESS_KEY='"<AK>"' \
    -DSECRET_KEY='"<SK>"' \
    -DHOST_NAME='"jijia-test-bucket.jp-tyo-1.linodeobjects.com"' \
    -DREGION='"jp-tyo-1"' \
    new_linode_upload_apr.1-git.c \
    -lcurl \
    -lssl \
    -lcrypto \
    -o tmp_test

  stat_test_cleanup
}

function maa_apr() {
  echo maa_apr
  gcc $1 \
    -fdiagnostics-color=always \
    -DACCESS_KEY='"<AK>"' \
    -DSECRET_KEY='"<SK>"' \
    -DHOST_NAME='"jijia-test-bucket.in-maa-1.linodeobjects.com"' \
    -DREGION='"in-maa"' \
    new_linode_upload_apr.1-git.c \
    -lcurl \
    -lssl \
    -lcrypto \
    -o tmp_test

  stat_test_cleanup
}

function tyo_dec() {
  echo tyo_dec
  gcc $1 \
    -fdiagnostics-color=always \
    -DACCESS_KEY='"<AK>"' \
    -DSECRET_KEY='"<SK>"' \
    -DHOST_NAME='"jijia-test-bucket.jp-tyo-1.linodeobjects.com"' \
    -DREGION='"jp-tyo-1"' \
    linode_upload.dec.20.c \
    -lcurl \
    -lssl \
    -lcrypto \
    -o tmp_test

  stat_test_cleanup
}

function maa_dec() {
  echo maa_dec
  gcc $1 \
    -fdiagnostics-color=always \
    -DACCESS_KEY='"<AK>"' \
    -DSECRET_KEY='"<SK>"' \
    -DHOST_NAME='"jijia-test-bucket.in-maa-1.linodeobjects.com"' \
    -DREGION='"in-maa"' \
    linode_upload.dec.20.c \
    -lcurl \
    -lssl \
    -lcrypto \
    -o tmp_test

  stat_test_cleanup
}

runAll() {
  echo ++++++++++++++++++++
  echo ++++++++++++++++++++
  tyo_apr -w
  tyo_dec -w
  echo ++++++++++++++++++++
  echo ++++++++++++++++++++
  maa_apr -w
  maa_dec -w
}
