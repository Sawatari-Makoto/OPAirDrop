#!/system/bin/sh
# Build identities of the kernel modules shipped in this package.
#
# NOTE_<name> is the build id of the .ko file in payload/kernel. The packager
# fills it in. It identifies what this package installs, never what the
# target device originally shipped.
#
# The stock build ids of a device are deliberately NOT stored here: they
# differ per device and per firmware, so a value baked in at build time
# would reject every other device. Each device reads its own ids at install
# time and stores them in /data/adb/opairshare/kernel-identity.sh, which the
# runtime loads through bin/common.sh. Nothing in this file is device
# specific.
NOTE_mac80211=BUILD_REQUIRED
NOTE_qca_cld3_peach_v2=BUILD_REQUIRED
NOTE_wonder=BUILD_REQUIRED
NOTE_opairshare_wonder_provider=BUILD_REQUIRED
# Kernel ABI family and page-size flavour the payload was compiled against.
# A .ko carries a vermagic string, so it only loads on a kernel of the same
# family. The runtime refuses to swap the drivers on any other kernel.
KERNEL_ABI_FAMILY=BUILD_REQUIRED
KERNEL_ABI_PAGESIZE=BUILD_REQUIRED
