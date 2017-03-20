To run all tests using JUnit XML runner you should perform following steps:

1) Clone repository

2) To execute tests you need to specify xml with tests.

 - 2.1 To execute test from suite.xml execute:

```bash
$ mvn test -Dtest=XmlRunnerSuite -DtestsXml=suite.xml
```
