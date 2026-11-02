package com.delta.common.security.utils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
public class PrivateNameSerializer extends JsonSerializer<String>{
 @Override public void serialize(String value,JsonGenerator out,SerializerProvider provider)throws IOException{
  boolean privileged=false;try{privileged=java.util.Set.of("ADMIN","CS").contains(SecurityUtils.getUserType());}catch(Exception ignored){}
  if(privileged)out.writeString(value);else out.writeString("");
 }
}
