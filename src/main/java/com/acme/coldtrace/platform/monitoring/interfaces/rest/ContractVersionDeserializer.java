package com.acme.coldtrace.platform.monitoring.interfaces.rest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;

/** Versions are integers on the wire; never truncate decimals or coerce strings. */
public class ContractVersionDeserializer extends JsonDeserializer<Integer> {
  @Override
  public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
      throw JsonMappingException.from(parser, "Contract version must be a JSON integer");
    }
    return parser.getIntValue();
  }
}
