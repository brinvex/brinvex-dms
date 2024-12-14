module test.com.brinvex.dms {
    requires com.brinvex.dms;
    requires org.slf4j;
    requires org.junit.jupiter.api;
    requires org.junit.jupiter.engine;
    opens test.com.brinvex.dms to org.junit.platform.commons;
}