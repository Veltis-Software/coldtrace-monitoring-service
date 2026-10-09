# coldtrace-monitoring-service

## Independent repository build

Use Java 21 and Maven. Install the sibling `coldtrace-shared` revision
`08ab5ee5321e3d7e5e4ec0b3c454a5f5da9bfcc3` first: `mvn -f ../coldtrace-shared/pom.xml install`.
Then run `mvn verify` in this repository.

Build its standalone image with
`docker build --build-context shared=../coldtrace-shared -t coldtrace-monitoring-service:tp1 .`.
The workflow checks out the same shared revision and publishes test artifacts.
Remote execution requires publishing the prepared repositories first.
The source and architecture audit are in `Veltis-Software/coldtrace-services`.
