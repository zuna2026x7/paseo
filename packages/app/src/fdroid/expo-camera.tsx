import { useCallback, useEffect, useRef, useState } from "react";
import {
  NativeModules,
  PermissionsAndroid,
  Pressable,
  StyleSheet,
  Text,
  type StyleProp,
  type ViewStyle,
} from "react-native";
import { useTranslation } from "react-i18next";

// F-Droid / GMS-free replacement for expo-camera, used only by the Android
// F-Droid build (see metro.config.cjs fdroidModuleOverrides). It implements
// exactly the surface the pairing scan screen uses — useCameraPermissions
// and CameraView with onBarcodeScanned — backed by the paseo-qrscan native
// module (CameraX + ZXing, no Google services). Tapping the camera area
// opens the full-screen scanner; a successful decode is reported through
// onBarcodeScanned just like expo-camera.

interface CameraPermission {
  status: "granted" | "denied";
  granted: boolean;
  canAskAgain: boolean;
  expires: "never";
}

interface BarcodeScanningResultLike {
  type: string;
  data: string;
  cornerPoints: never[];
  boundingBox: {
    origin: { x: number; y: number };
    size: { width: number; height: number };
  };
}

interface PaseoQrScanModule {
  scanQr: () => Promise<string | null>;
}

const PaseoQrScan = NativeModules.PaseoQrScan as PaseoQrScanModule | undefined;

function toPermission(granted: boolean, canAskAgain: boolean): CameraPermission {
  return {
    status: granted ? "granted" : "denied",
    granted,
    canAskAgain,
    expires: "never",
  };
}

function samePermission(a: CameraPermission | null, b: CameraPermission): boolean {
  return (
    a !== null &&
    a.status === b.status &&
    a.granted === b.granted &&
    a.canAskAgain === b.canAskAgain
  );
}

export function useCameraPermissions() {
  const [permission, setPermission] = useState<CameraPermission | null>(null);
  const permissionRef = useRef<CameraPermission | null>(null);

  const applyPermission = useCallback((next: CameraPermission) => {
    // Keep state identity stable when nothing changed so effects that
    // depend on `permission` do not re-fire (and re-prompt) in a loop.
    if (samePermission(permissionRef.current, next)) return;
    permissionRef.current = next;
    setPermission(next);
  }, []);

  useEffect(() => {
    void PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.CAMERA).then((granted) =>
      applyPermission(toPermission(granted, true)),
    );
  }, [applyPermission]);

  const requestPermission = useCallback(async () => {
    const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA);
    const next = toPermission(
      result === PermissionsAndroid.RESULTS.GRANTED,
      result !== PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN,
    );
    applyPermission(next);
    return next;
  }, [applyPermission]);

  return [permission, requestPermission] as const;
}

const styles = StyleSheet.create({
  scanner: {
    backgroundColor: "#111111",
    justifyContent: "center",
    alignItems: "center",
    gap: 8,
    padding: 24,
  },
  scannerTitle: {
    color: "#ffffff",
    fontSize: 17,
    fontWeight: "600",
    textAlign: "center",
  },
  scannerHint: {
    color: "#bbbbbb",
    fontSize: 14,
    textAlign: "center",
  },
});

export function CameraView(props: {
  style?: StyleProp<ViewStyle>;
  facing?: string;
  barcodeScannerSettings?: unknown;
  onBarcodeScanned?: (result: BarcodeScanningResultLike) => void;
}) {
  const { t } = useTranslation();
  const scanningRef = useRef(false);
  const onBarcodeScannedRef = useRef(props.onBarcodeScanned);
  onBarcodeScannedRef.current = props.onBarcodeScanned;

  const handlePress = useCallback(async () => {
    if (scanningRef.current || !PaseoQrScan) return;
    scanningRef.current = true;
    try {
      const data = await PaseoQrScan.scanQr();
      if (typeof data === "string" && data.length > 0) {
        onBarcodeScannedRef.current?.({
          type: "qr",
          data,
          cornerPoints: [],
          boundingBox: {
            origin: { x: 0, y: 0 },
            size: { width: 0, height: 0 },
          },
        });
      }
    } catch {
      // Scanner dismissed or unavailable; tapping again retries.
    } finally {
      scanningRef.current = false;
    }
  }, []);

  return (
    <Pressable style={[styles.scanner, props.style]} onPress={handlePress}>
      <Text style={styles.scannerTitle}>
        {t("pairing.connectionMethods.scanQr.title", {
          defaultValue: "Scan QR code",
        })}
      </Text>
      <Text style={styles.scannerHint}>
        {t("pairing.scan.tapToScan", { defaultValue: "Tap to open the scanner" })}
      </Text>
    </Pressable>
  );
}
