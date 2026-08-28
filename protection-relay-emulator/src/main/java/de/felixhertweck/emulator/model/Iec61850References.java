package de.felixhertweck.emulator.model;

public final class Iec61850References {

    public static final String IED_NAME = "SIP1";

    private Iec61850References() {}

    // MMXU — hosted in SIP1VI3p1_OperationalValues with Siemens LN prefixes:
    //   PPRE_MMXU1 carries power/frequency, RPRE_MMXU1 carries currents/voltages.
    public static final String MMXU_HZ_MAG_F = "SIP1VI3p1_OperationalValues/PPRE_MMXU1.Hz.mag.f";
    public static final String MMXU_TOTW_MAG_F =
            "SIP1VI3p1_OperationalValues/PPRE_MMXU1.TotW.mag.f";
    public static final String MMXU_A_PHSA_MAG_F =
            "SIP1VI3p1_OperationalValues/RPRE_MMXU1.A.phsA.cVal.mag.f";
    public static final String MMXU_A_PHSB_MAG_F =
            "SIP1VI3p1_OperationalValues/RPRE_MMXU1.A.phsB.cVal.mag.f";
    public static final String MMXU_A_PHSC_MAG_F =
            "SIP1VI3p1_OperationalValues/RPRE_MMXU1.A.phsC.cVal.mag.f";
    public static final String MMXU_PPV_PHSAB_MAG_F =
            "SIP1VI3p1_OperationalValues/RPRE_MMXU1.PPV.phsAB.cVal.mag.f";
    public static final String MMXU_PPV_PHSBC_MAG_F =
            "SIP1VI3p1_OperationalValues/RPRE_MMXU1.PPV.phsBC.cVal.mag.f";
    public static final String MMXU_PPV_PHSCA_MAG_F =
            "SIP1VI3p1_OperationalValues/RPRE_MMXU1.PPV.phsCA.cVal.mag.f";

    // PTOC — hosted in SIP1VI3p1_5051OC3phase1 as ID_PTOC1 (definite-time 50/51 stage)
    public static final String PTOC_STR_GENERAL = "SIP1VI3p1_5051OC3phase1/ID_PTOC1.Str.general";
    public static final String PTOC_OP_GENERAL = "SIP1VI3p1_5051OC3phase1/ID_PTOC1.Op.general";

    // XCBR — hosted in SIP1CB1. Status-only on the physical SIPROTEC (ctlModel=0);
    // kept here only for status reads and to explicitly reject stray control writes.
    public static final String XCBR_POS_STVAL = "SIP1CB1/XCBR1.Pos.stVal";
    public static final String XCBR_POS_OPER_CTLVAL = "SIP1CB1/XCBR1.Pos.Oper.ctlVal";
    public static final String XCBR_POS_CTL_MODEL = "SIP1CB1/XCBR1.Pos.ctlModel";

    // CSWI — hosted in SIP1CB1. The actual control path on the physical SIPROTEC
    // (sbo-with-normal-security, matching what the Phase 2a OT proxy exposes downstream).
    public static final String CSWI_POS_OPER_CTLVAL = "SIP1CB1/CSWI1.Pos.Oper.ctlVal";
    public static final String CSWI_POS_CTL_MODEL = "SIP1CB1/CSWI1.Pos.ctlModel";
}
