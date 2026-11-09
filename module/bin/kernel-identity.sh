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
NOTE_mac80211=040000001400000003000000474e5500949d245db3d6bec6517af827364f10bbe16c324c
NOTE_qca_cld3_peach_v2=040000001400000003000000474e550093a67e5b7e41b058c497304b8e40ed92e70aa1e5
NOTE_wonder=040000001400000003000000474e5500c8c56e68df0d5ac07bda854f0c999405569393a1
NOTE_oneplus13_wonder_provider=040000001400000003000000474e5500c909b5692ed92531f7f424faf53e114585d95c4a
# Kernel ABI family and page-size flavour the payload was compiled against.
# A .ko carries a vermagic string, so it only loads on a kernel of the same
# family. The runtime refuses to swap the drivers on any other kernel.
KERNEL_ABI_FAMILY=6.6.118-android15-8
KERNEL_ABI_PAGESIZE=4k
