package io.opencode.loopper.workflow;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkflowVerificationTest {
    @Test void contentPreservesTheExactExpectationAndDoesNotAllowEmptyContracts() {
        var check=new WorkflowVerification.Check("检查配置","FILE_CONTENT","src/example.txt","  value\n","EXACT");
        var spec=new WorkflowVerification(1,"code",List.of(check));spec.validate();assertThat(spec.checks().getFirst().expected()).isEqualTo("  value\n");
        assertThatThrownBy(()->new WorkflowVerification(1,"code",List.of()).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new WorkflowVerification(2,"code",List.of(check)).validate()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pathsCannotEscapeOrSelectDirectoriesAndExecutionKindsStayClosed() {
        for(String path:List.of("../secret","src/../secret","/tmp/secret","C:/file","src\\file","src/","src//file","src\nfile"))
            assertThatThrownBy(()->spec("FILE_HASH",path,"0".repeat(64),null).validate()).isInstanceOf(IllegalArgumentException.class);
        for(String type:List.of("PROCESS","BROWSER","HTTP_STATUS","DATABASE_QUERY","FILE_EXISTS"))
            assertThatThrownBy(()->spec(type,"a.txt",null,null).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->spec("FILE_CONTENT","a.txt","value","REGEX").validate()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void hashAndRemovalContractsCannotSilentlyIgnoreInvalidExpectations() {
        assertThatThrownBy(()->spec("FILE_HASH","a.txt","not-a-hash",null).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->spec("FILE_NOT_EXISTS","a.txt","expected",null).validate()).isInstanceOf(IllegalArgumentException.class);
        spec("FILE_HASH","a.txt","A".repeat(64),null).validate();spec("FILE_NOT_EXISTS","a.txt",null,null).validate();
    }
    private WorkflowVerification spec(String type,String path,String expected,String mode){return new WorkflowVerification(1,"code",List.of(new WorkflowVerification.Check("检查",type,path,expected,mode)));}
}
