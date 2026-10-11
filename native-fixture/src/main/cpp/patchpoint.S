.text
.global deploy_fixture_patchpoint
.type deploy_fixture_patchpoint, %function
deploy_fixture_patchpoint:
    mov x0, #7
    ret
.size deploy_fixture_patchpoint, .-deploy_fixture_patchpoint
