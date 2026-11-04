#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -encoding UTF-8 -d "$work" "$root/app/src/main/java/com/oshare/airdrop/DiscoveryChannels.java" "$root/tests/DiscoveryChannelsTest.java" "$root/app/src/main/java/com/oshare/airdrop/CallbackFactory.java" "$root/tests/CallbackFactoryTest.java" "$root/app/src/main/java/com/oshare/airdrop/OutgoingTransfers.java" "$root/tests/OutgoingTransfersTest.java" "$root/app/src/main/java/com/oshare/airdrop/IncomingTransfers.java" "$root/tests/IncomingTransfersTest.java" "$root/app/src/main/java/com/oshare/airdrop/AirDropPresentation.java" "$root/tests/AirDropPresentationTest.java"
java -cp "$work" CallbackFactoryTest
java -cp "$work" OutgoingTransfersTest
java -cp "$work" IncomingTransfersTest
javac -encoding UTF-8 -d "$work" "$root/app/src/main/java/com/oshare/airdrop/AirDropName.java" "$root/tests/AirDropNameTest.java"
java -cp "$work" AirDropNameTest

java -cp "$work" AirDropPresentationTest

java -cp "$work" DiscoveryChannelsTest

javac -encoding UTF-8 -d "$work" "$root/app/src/main/java/com/oshare/airdrop/DiscoverySession.java" "$root/tests/DiscoverySessionTest.java"
java -cp "$work" com.oshare.airdrop.DiscoverySessionTest
javac -encoding UTF-8 -d "$work" "$root/app/src/main/java/com/oshare/airdrop/ShareMode.java" "$root/tests/ShareModeTest.java"
java -cp "$work" com.oshare.airdrop.ShareModeTest
javac -encoding UTF-8 -cp "$work" -d "$work" "$root/app/src/main/java/com/oshare/airdrop/TransferHistory.java" "$root/tests/TransferHistoryTest.java"
java -cp "$work" com.oshare.airdrop.TransferHistoryTest
javac -encoding UTF-8 -d "$work/mode-runtime" "$root"/tests/mode-runtime/android/*/*.java \
    "$root"/tests/mode-runtime/com/oshare/airdrop/*.java \
    "$root/app/src/main/java/com/oshare/airdrop/ShareMode.java" \
    "$root/app/src/main/java/com/oshare/airdrop/ShareModeRuntime.java"
java -cp "$work/mode-runtime" com.oshare.airdrop.ShareModeRuntimeTest

javac -encoding UTF-8 -cp "$work/mode-runtime" -d "$work/mode-runtime" \
    "$root"/tests/mode-hooks/io/github/libxposed/api/*.java \
    "$root"/tests/mode-hooks/com/oshare/airdrop/*.java \
    "$root/app/src/main/java/com/oshare/airdrop/ShareModeHooks.java"
java -cp "$work/mode-runtime" com.oshare.airdrop.ShareModeHooksTest

javac -encoding UTF-8 -d "$work" "$root/app/src/main/java/com/oshare/airdrop/BridgeReconnect.java" "$root/tests/BridgeReconnectTest.java"
java -cp "$work" com.oshare.airdrop.BridgeReconnectTest

javac -encoding UTF-8 -d "$work" "$root/app/src/main/java/com/oshare/airdrop/OShareApkFiles.java" "$root/tests/OShareApkFilesTest.java"
java -cp "$work" com.oshare.airdrop.OShareApkFilesTest

javac -encoding UTF-8 -d "$work" "$root/app/src/main/java/com/oshare/airdrop/ReceiveDirectory.java" "$root/tests/ReceiveDirectoryTest.java" "$root/app/src/main/java/com/oshare/airdrop/ReceiveUiPolicy.java" "$root/tests/ReceiveUiPolicyTest.java"
java -cp "$work" com.oshare.airdrop.ReceiveDirectoryTest
java -cp "$work" com.oshare.airdrop.ReceiveUiPolicyTest

javac -encoding UTF-8 -cp "$work" -d "$work" "$root/app/src/main/java/com/oshare/airdrop/SendUiState.java" "$root/tests/SendUiStateTest.java"
java -cp "$work" com.oshare.airdrop.SendUiStateTest

javac -encoding UTF-8 -cp "$work" -d "$work" "$root/app/src/main/java/com/oshare/airdrop/ChooserTargets.java" \
    "$root/app/src/main/java/com/oshare/airdrop/ChooserSessions.java" \
    "$root/app/src/main/java/com/oshare/airdrop/PendingShares.java" \
    "$root/app/src/main/java/com/oshare/airdrop/OutgoingPreparations.java" "$root/tests/ChooserIntegrationTest.java"
java -cp "$work" com.oshare.airdrop.ChooserIntegrationTest
