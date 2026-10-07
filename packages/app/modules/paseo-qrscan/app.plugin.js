const { withMainApplication } = require("expo/config-plugins");

const PACKAGE_LIST = "PackageList(this).packages.apply {";
const REGISTRATION = "add(sh.paseo.qrscan.PaseoQrScanPackage())";

function configureQrScanPackage(contents) {
  if (contents.includes(REGISTRATION)) return contents;
  if (!contents.includes(PACKAGE_LIST)) {
    throw new Error(
      "Could not register Android QR scan package in MainApplication",
    );
  }
  return contents.replace(
    PACKAGE_LIST,
    `${PACKAGE_LIST}\n              ${REGISTRATION}`,
  );
}

module.exports = (config) =>
  withMainApplication(config, (mod) => {
    mod.modResults.contents = configureQrScanPackage(mod.modResults.contents);
    return mod;
  });
module.exports.configureQrScanPackage = configureQrScanPackage;
