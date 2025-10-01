# Font Assets

The Roboto Flex variable font (`app/src/main/res/font/roboto_flex_variable.ttf`) was downloaded from the official Google Fonts repository:
https://github.com/google/fonts/raw/main/ofl/robotoflex/RobotoFlex%5BGX,opsz,wdth,wght%5D.ttf

The previous placeholder file contained an HTML download page, which caused runtime font loading failures during screenshot instrumentation tests.

> **Note:** Android's resource merger only accepts font binaries (`.ttf`, `.ttc`, `.otf`) or font family XML descriptors inside `app/src/main/res/font/`. Any documentation should live in this `docs/fonts/` directory to keep Gradle builds green.
