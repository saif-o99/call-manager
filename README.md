# @qusaieilouti99/call-manager

Call manager

## Installation


```sh
npm install @qusaieilouti99/call-manager react-native-nitro-modules

> `react-native-nitro-modules` is required as this library relies on [Nitro Modules](https://nitro.margelo.com/).
```


## Usage


```js
import { multiply } from '@qusaieilouti99/call-manager';

// ...

const result = multiply(3, 7);
```

## Android Manifest Notes

`CallActivity` is an internal incoming-call UI surface. Host apps should keep it non-exported unless they have a deliberate, reviewed reason to expose it.

When declaring it in the host manifest, keep `android:exported="false"` and only override the specific attributes you actually need. Example:

```xml
<manifest xmlns:tools="http://schemas.android.com/tools">
  <application>
    <activity
      android:name="com.margelo.nitro.qusaieilouti99.callmanager.CallActivity"
      android:theme="@style/YourIncomingCallTheme"
      android:exported="false"
      tools:replace="android:theme" />
  </application>
</manifest>
```


## Contributing

See the [contributing guide](CONTRIBUTING.md) to learn how to contribute to the repository and the development workflow.

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
