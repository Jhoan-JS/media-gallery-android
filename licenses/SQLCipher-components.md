# SQLCipher vendor notices

The Maven artifact `net.zetetic:sqlcipher-android:4.19.1` links to Zetetic's
license page. Its Community Edition license is retained unchanged in
[SQLCipher-Community.txt](SQLCipher-Community.txt).

The vendor's notices identify SQLite and SQLite Android Bindings as public
domain. SQLCipher Android 4.19.1 uses LibTomCrypt as its default crypto provider;
its pinned submodule license is retained in [LibTomCrypt.txt](LibTomCrypt.txt).
This distribution uses that license's public-domain option and preserves its
complete dual-license text. OpenSSL is an optional alternative described by
the vendor, rather than the default provider in this Maven build.

Gradle resolves the libraries used by the Android builds. This repository
keeps the supplied license texts; generated APKs are not committed to Git.

[SQLCipher licenses and notices](https://www.zetetic.net/sqlcipher/license/)

[SQLCipher Android 4.19.1 build documentation](https://github.com/sqlcipher/sqlcipher-android/blob/v4.19.1/README.md)
