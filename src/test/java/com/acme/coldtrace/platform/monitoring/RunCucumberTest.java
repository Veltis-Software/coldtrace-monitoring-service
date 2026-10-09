package com.acme.coldtrace.platform.monitoring;
import org.junit.platform.suite.api.*;
import static io.cucumber.junit.platform.engine.Constants.*;
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key=GLUE_PROPERTY_NAME,value="com.acme.coldtrace.platform.monitoring")
@ConfigurationParameter(key=PLUGIN_PROPERTY_NAME,value="pretty,json:target/cucumber.json,html:target/cucumber.html")
public class RunCucumberTest {}
