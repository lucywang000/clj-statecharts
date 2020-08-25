#!/bin/bash

set -e
set -x

HUGO_VERSION=0.74.3
TMP_REPO_DIR=/tmp/clj-statecharts

install_hugo() {
    pushd /tmp
    wget -q "https://github.com/gohugoio/hugo/releases/download/v${HUGO_VERSION}/hugo_extended_${HUGO_VERSION}_Linux-64bit.tar.gz"
    tar xf hugo_extended_${HUGO_VERSION}_Linux-64bit.tar.gz hugo
    chmod +x hugo
    popd
}

build_docs() {
    git submodule init
    git submodule update
    pushd docs/
    /tmp/hugo -D
    popd
}

init_tmp_repo() {
    rm -rf $TMP_REPO_DIR
    mkdir -p $TMP_REPO_DIR
    pushd $TMP_REPO_DIR
    git init
    git remote add origin https://github.com/lucywang000/clj-statecharts.git
    # create a new clear branch
    git checkout --orphan "branch-$(date +%s)"
    popd

}

copy_docs() {
    cp -rpvfa docs/public/* $TMP_REPO_DIR
}

commit_and_push_pages() {
    cd $TMP_REPO_DIR
    git add -f .
    git config user.email "wxitb2017@gmail.com"
    git config user.name "Github Actions Robot"
    git commit -a -m 'update'
    git push -f origin HEAD:gh-pages
}

install_hugo
build_docs
init_tmp_repo
copy_docs
commit_and_push_pages
